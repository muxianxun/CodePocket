package com.dsh.codepocket.runtime

import com.dsh.codepocket.Diag
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Unpacks Debian packages (Termux's `.deb`) without any external tool.
 *
 * Layout of a .deb: `!<arch>` + members `debian-binary`, `control.tar.*`, `data.tar.xz`.
 * The payload is a tar tree whose paths look like
 * `./data/data/com.termux/files/usr/bin/clang`; that Termux prefix is stripped so a package
 * lands under `<dest>/usr/...`, and the loader path is supplied at exec time instead
 * (Termux binaries carry an RPATH pointing at their original prefix).
 *
 * Both formats are parsed by hand — a few hundred lines beats pulling in a package manager,
 * and it keeps full control over the executable bits, which matter here.
 */
object ToolchainInstaller {

    private const val TERMUX_PREFIX = "data/data/com.termux/files/"

    data class Result(val files: Int, val sample: List<String>, val errors: List<String>)

    fun extractDeb(deb: File, dest: File): Result {
        val extracted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        dest.mkdirs()

        deb.inputStream().buffered(64 * 1024).use { raw ->
            skipArMagic(raw)
            while (true) {
                val header = readFully(raw, 60) ?: break
                if (header.size < 60) break
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull()
                if (size == null) {
                    errors += "无法解析成员大小: $name"
                    break
                }
                if (name.startsWith("data.tar")) {
                    val bounded = BoundedInputStream(raw, size)
                    val stream: InputStream = if (name.endsWith(".xz")) {
                        XZInputStream(bounded)
                    } else {
                        bounded
                    }
                    extractTar(stream, dest, extracted, errors)
                    return Result(extracted.size, extracted.take(12), errors)
                }
                // Not the payload: skip it (ar members are padded to an even offset).
                skipFully(raw, size + size % 2)
            }
        }
        return Result(0, emptyList(), errors + "归档里没有找到 data.tar.*")
    }

    // ------------------------------------------------------------------ ar

    private fun skipArMagic(input: InputStream) {
        val magic = readFully(input, 8)
        if (magic == null || String(magic, Charsets.US_ASCII) != "!<arch>\n") {
            throw IllegalArgumentException("不是 ar 归档（.deb 应以 !<arch> 开头）")
        }
    }

    // ------------------------------------------------------------------ tar

    private fun extractTar(
        input: InputStream,
        dest: File,
        extracted: MutableList<String>,
        errors: MutableList<String>,
    ) {
        var pendingLongName: String? = null
        while (true) {
            val header = readFully(input, 512) ?: break
            if (header.size < 512) break
            if (header.all { it == 0.toByte() }) break

            var name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            pendingLongName?.let { name = it; pendingLongName = null }

            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8)
            val typeFlag = header[156].toInt().toChar()

            val relative = normalize(name)
            val target = File(dest, relative)
            // Never let an archive escape the destination directory.
            if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                errors += "跳过越界路径: $name"
                skipFully(input, size + tarPadding(size))
                continue
            }

            when (typeFlag) {
                'L' -> {
                    val data = readFully(input, size.toInt())
                    pendingLongName = data?.let { String(it, Charsets.UTF_8).trimEnd('\u0000') }
                    skipFully(input, tarPadding(size))
                }

                '5' -> {
                    target.mkdirs()
                    skipFully(input, tarPadding(size))
                }

                '2' -> {
                    // Symlinks inside a package point at other files in the same tree.
                    val link = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000')
                    target.parentFile?.mkdirs()
                    runCatching { java.nio.file.Files.createSymbolicLink(target.toPath(), File(link).toPath()) }
                    skipFully(input, tarPadding(size))
                }

                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    copyLimited(input, target, size)
                    // The executable bit is the whole point for a toolchain.
                    if (mode and 0b001_000_000 != 0L) target.setExecutable(true, false)
                    extracted += relative
                    skipFully(input, tarPadding(size))
                }

                else -> skipFully(input, size + tarPadding(size))
            }
        }
    }

    /** `./data/data/com.termux/files/usr/bin/clang` -> `usr/bin/clang`. */
    private fun normalize(raw: String): String {
        var name = raw.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        name = name.removePrefix("/").removePrefix(TERMUX_PREFIX)
        return name.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val text = String(header, offset, length, Charsets.US_ASCII).trim().trimEnd('\u0000').trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: runCatching { text.toLong() }.getOrDefault(0L)
    }

    private fun tarPadding(size: Long): Long = (512 - size % 512) % 512

    // ------------------------------------------------------------------ io helpers

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) return if (read == 0) null else buffer.copyOf(read)
            read += n
        }
        return buffer
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    private fun copyLimited(input: InputStream, target: File, size: Long) {
        var remaining = size
        target.outputStream().buffered(64 * 1024).use { output: OutputStream ->
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) break
                output.write(buffer, 0, n)
                remaining -= n
            }
        }
    }

    /** Prevents a member from reading past its own end when xz decoding. */
    private class BoundedInputStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, source.available().toLong()).toInt()
    }

    /** Unpacks a .deb that was placed in the app's external files dir by the caller. */
    fun extractProbe(context: android.content.Context): String {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        val deb = File(external, "probe.deb")
        if (!deb.exists()) {
            return "（未找到 ${deb.name}，跳过解包自检）"
        }
        val dest = File(context.filesDir, "toolchains/probe")
        val started = System.currentTimeMillis()
        return try {
            deriveResult(deb, dest, started)
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            Diag.log("toolchain", "extract failed: ${cause.javaClass.simpleName} ${cause.message}")
            "解包失败: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    private fun deriveResult(deb: File, dest: File, started: Long): String {
        // Start clean so the reported count is meaningful.
        dest.deleteRecursively()
        val result = extractDeb(deb, dest)
        val elapsed = System.currentTimeMillis() - started
        Diag.log(
            "toolchain",
            "extracted ${deb.name} (${deb.length() / 1024} KB) -> ${result.files} files in ${elapsed}ms",
        )
        val executables = dest.walkTopDown()
            .filter { it.isFile && it.canExecute() }
            .take(6)
            .map { it.relativeTo(dest).path }
            .toList()
        return buildString {
            append("包: ").append(deb.name).append(" (").append(deb.length() / 1024).append(" KB)\n")
            append("解包: ").append(result.files).append(" 个文件, ").append(elapsed).append("ms\n")
            if (result.errors.isNotEmpty()) {
                append("错误: ").append(result.errors.take(3).joinToString("; ")).append('\n')
            }
            append("可执行文件: ").append(executables.joinToString(", ").ifEmpty { "（无）" }).append('\n')
            append("样例: ").append(result.sample.take(6).joinToString(", ")).append('\n')
            append(
                if (result.files > 0) "结论：✅ ar + xz + tar 解包链路可用"
                else "结论：❌ 解包失败",
            )
        }
    }
}
