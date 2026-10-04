package com.dsh.codepocket.runtime

import android.content.Context
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Reads Termux's APT index so language toolchains can be fetched on demand.
 *
 * The index is a Debian `Packages` file: blocks separated by blank lines, each with
 * `Package` / `Version` / `Filename` / `Size` / `Depends`. `Depends` uses `,` for AND,
 * `|` for alternatives and parentheses for version constraints — an alternative group must
 * be resolved as a group, otherwise reporting claims a missing package when another
 * alternative was perfectly available.
 *
 * Bundling a whole package manager would be overkill; this resolves one dependency tree and
 * reports its real download size before anything big is downloaded.
 */
object TermuxRepo {

    private const val MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

    data class Pkg(
        val name: String,
        val version: String,
        val filename: String,
        val sizeBytes: Long,
        /** Each inner list is one dependency group; any member satisfies it. */
        val depends: List<List<String>>,
    )

    fun indexUrl(arch: String) = "$MIRROR/dists/stable/main/binary-$arch/Packages"

    fun packageUrl(filename: String) = "$MIRROR/$filename"

    fun parseIndex(text: String): Map<String, Pkg> {
        val result = linkedMapOf<String, Pkg>()
        for (block in text.replace("\r\n", "\n").split("\n\n")) {
            if (block.isBlank()) continue
            var name = ""
            var version = ""
            var filename = ""
            var size = 0L
            var depends: List<List<String>> = emptyList()
            for (line in block.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
                    line.startsWith("Version: ") -> version = line.removePrefix("Version: ").trim()
                    line.startsWith("Filename: ") -> filename = line.removePrefix("Filename: ").trim()
                    line.startsWith("Size: ") -> size = line.removePrefix("Size: ").trim().toLongOrNull() ?: 0L
                    line.startsWith("Depends: ") -> depends = parseDepends(line.removePrefix("Depends: "))
                }
            }
            if (name.isNotEmpty()) result[name] = Pkg(name, version, filename, size, depends)
        }
        return result
    }

    /** `libc++ (>= 27), binutils | binutils-gold` -> [[libc++], [binutils, binutils-gold]] */
    fun parseDepends(raw: String): List<List<String>> =
        raw.split(',')
            .map { group ->
                group.split('|')
                    .map { it.trim().substringBefore('(').trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("$") }
            }
            .filter { it.isNotEmpty() }

    data class Plan(val packages: List<Pkg>, val missing: List<String>) {
        val totalBytes: Long get() = packages.sumOf { it.sizeBytes }
    }

    fun resolve(root: String, index: Map<String, Pkg>): Plan {
        val chosen = linkedMapOf<String, Pkg>()
        val missing = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (chosen.containsKey(name) || missing.contains(name)) continue
            val pkg = index[name]
            if (pkg == null) {
                missing += name
                continue
            }
            chosen[name] = pkg
            for (group in pkg.depends) {
                val satisfied = group.firstOrNull { index.containsKey(it) }
                queue += satisfied ?: group.first()
            }
        }
        return Plan(chosen.values.toList(), missing.toList())
    }

    /** Downloads the index (a few MB) and reports the clang plan — no toolchain download. */
    suspend fun planReport(context: Context): String = withContext(Dispatchers.IO) {
        val arch = NativeToolchain.termuxArch()
        val cache = File(context.filesDir, "toolchains/cache").apply { mkdirs() }
        val file = File(cache, "Packages-$arch")
        val out = StringBuilder()
        try {
            val started = System.currentTimeMillis()
            Downloader.download(indexUrl(arch), file).getOrThrow()
            val index = parseIndex(file.readText())
            val elapsed = System.currentTimeMillis() - started
            out.append("索引($arch): ").append(index.size).append(" 个包, ")
                .append(file.length() / 1024).append(" KB, ").append(elapsed).append("ms\n")

            val plan = resolve("clang", index)
            out.append("clang 依赖: ").append(plan.packages.size).append(" 个包, 下载约 ")
                .append(String.format(Locale.US, "%.1f", plan.totalBytes / 1024.0 / 1024.0))
                .append(" MB\n")
            if (plan.missing.isNotEmpty()) {
                out.append("未能解析的依赖: ").append(plan.missing.joinToString(", ")).append('\n')
            }
            out.append("最大的几个: ")
            out.append(
                plan.packages.sortedByDescending { it.sizeBytes }.take(6).joinToString(", ") {
                    "${it.name} ${it.sizeBytes / 1024 / 1024}MB"
                },
            )
            Diag.log("termux", out.toString().replace('\n', ' '))
            out.toString()
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            out.append("索引解析失败: ").append(cause.javaClass.simpleName).append(": ").append(cause.message)
            Diag.log("termux", out.toString())
            out.toString()
        }
    }
}
