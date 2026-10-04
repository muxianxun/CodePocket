package com.dsh.codepocket.runtime

import android.content.Context
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Rust, installed on demand from the Termux repository.
 *
 * Mechanism is identical to C/C++: `rustc` must be exec()'ed, which the app is allowed to do
 * because it targets API 28. Two details make this work rather than merely seem to:
 *
 *  - The toolchain unpacks into the **same prefix as clang**. rustc does not link by itself;
 *    it shells out to a linker, so it needs `clang`/`cc` on PATH — which the C/C++ toolchain
 *    already put there.
 *  - The dependency closure comes from the package index, so cargo and rustc's own
 *    dependencies are resolved instead of being hard-coded into a list that would rot.
 *
 * Installation is lazy: the first `.rs` run downloads and unpacks it, reporting progress in
 * the editor's output panel. No files stay untracked — the closure is recorded so the
 * language manager can show what is installed.
 */
object RustToolchain {

    /** Termux ships the compiler and cargo together under `rust`; keep fallbacks in case that changes. */
    private val PACKAGE_CANDIDATES = listOf("rust", "rustc", "cargo")

    private const val HELLO = """
fn main() {
    println!("RUST_ON_ANDROID_OK");
    let sum: i32 = (1..=10).sum();
    println!("sum(1..=10)={}", sum);
    println!("arch={}", std::env::consts::ARCH);
}
"""

    fun root(context: Context) = File(context.filesDir, "toolchains/root")
    private fun usr(context: Context) = File(File(root(context), "usr").absolutePath)

    fun rustc(context: Context): File? = File(usr(context), "bin/rustc").takeIf { it.exists() }

    private fun environment(context: Context): Map<String, String> {
        val usr = usr(context)
        File(usr, "tmp").mkdirs()
        return mapOf(
            "PATH" to "${usr.path}/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${usr.path}/lib",
            "PREFIX" to usr.path,
            "TMPDIR" to File(usr, "tmp").path,
            "HOME" to File(root(context), "home").apply { mkdirs() }.path,
            "CARGO_HOME" to File(root(context), "cargo").apply { mkdirs() }.path,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
        )
    }

    private fun exec(context: Context, binary: File, args: List<String>, workDir: File): String {
        val command = listOf(binary.absolutePath) + args
        return try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply { environment().putAll(environment(context)) }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            "退出码=$code\n$output"
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            "执行失败: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)

    /** Public entry point for the language manager's explicit install button. */
    suspend fun install(context: Context, onProgress: (String) -> Unit): File =
        ensureInstalled(context, onProgress)

    /**
     * Downloads and unpacks the Rust toolchain if `rustc` is not present yet.
     * @return the rustc binary
     */
    private suspend fun ensureInstalled(context: Context, onProgress: (String) -> Unit): File {
        rustc(context)?.let { return it }

        val arch = NativeToolchain.termuxArch()
        val root = root(context).apply { mkdirs() }
        val cache = File(context.filesDir, "toolchains/cache").apply { mkdirs() }

        onProgress("• 拉取 Termux 索引 ($arch)…")
        val indexFile = File(cache, "Packages-$arch")
        val index = withContext(Dispatchers.IO) {
            if (!indexFile.exists() || indexFile.length() == 0L) {
                Downloader.download(TermuxRepo.indexUrl(arch), indexFile).getOrThrow()
            }
            TermuxRepo.parseIndex(indexFile.readText())
        }

        // Pick whichever of the candidate package names this index actually has.
        var plan: TermuxRepo.Plan? = null
        for (name in PACKAGE_CANDIDATES) {
            val candidate = TermuxRepo.resolve(name, index)
            if (candidate.packages.isNotEmpty()) {
                onProgress("• Rust 包: $name（依赖 ${candidate.packages.size} 个，约 ${mb(candidate.totalBytes)} MB）")
                plan = candidate
                break
            }
        }
        val resolved = plan ?: error("索引里找不到 ${PACKAGE_CANDIDATES.joinToString("/")}")

        var done = 0L
        for (pkg in resolved.packages.sortedByDescending { it.sizeBytes }) {
            onProgress("• 下载 ${pkg.name} ${mb(pkg.sizeBytes)} MB（${done * 100 / maxOf(1L, resolved.totalBytes)}%）")
            withContext(Dispatchers.IO) {
                val deb = File(cache, pkg.filename.substringAfterLast('/'))
                if (!deb.exists() || deb.length() != pkg.sizeBytes) {
                    Downloader.download(TermuxRepo.packageUrl(pkg.filename), deb).getOrThrow()
                }
                val unpacked = ToolchainInstaller.extractDeb(deb, root)
                Diag.log("rust", "unpacked ${pkg.name}: ${unpacked.files} files, ${unpacked.errors.size} errors")
            }
            done += pkg.sizeBytes
        }

        // The language manager keeps its own list; record what we installed so it can show it.
        runCatching {
            val record = File(context.filesDir, "toolchains/installed.txt")
            val merged = (record.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() } +
                resolved.packages.map { it.name }).distinct().sorted()
            record.writeText(merged.joinToString("\n") + "\n")
        }

        val binary = rustc(context)
            ?: error(
                "解包完成但找不到 bin/rustc；usr/bin 里有: " +
                    (File(usr(context), "bin").listFiles()?.take(12)?.joinToString { it.name } ?: "（空）"),
            )
        if (!binary.canExecute()) binary.setExecutable(true, false)
        onProgress("• rustc 已就绪: ${binary.relativeTo(root).path}")
        return binary
    }

    /** Compiles and runs one .rs file, installing the toolchain first if needed. */
    suspend fun compileAndRunSource(context: Context, source: File): String {
        val report = StringBuilder()
        val emit: (String) -> Unit = { line ->
            report.append(line).append('\n')
            Diag.log("rust", line.take(200))
        }
        return try {
            val compiler = ensureInstalled(context, emit)
            val work = File(context.filesDir, "rustbuild").apply { mkdirs() }
            // Output must live in internal storage: the shared filesystem is mounted noexec.
            val binary = File(work, source.nameWithoutExtension + "_bin")

            emit("• 编译 ${source.name} …")
            val compile = withContext(Dispatchers.IO) {
                exec(
                    context,
                    compiler,
                    listOf("-O", "-o", binary.absolutePath, source.absolutePath),
                    work,
                )
            }
            report.append("[rustc] ").append(compile.trim().take(1200)).append('\n')

            if (!binary.exists()) {
                report.append("❌ 编译未产出可执行文件")
                return report.toString()
            }
            if (!binary.canExecute()) binary.setExecutable(true, false)

            val run = withContext(Dispatchers.IO) { exec(context, binary, emptyList(), work) }
            report.append("[运行] ").append(run.trim())
            Diag.log("rust", "ran ${source.name}: ${run.take(200)}")
            report.toString()
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            report.append("❌ ").append(cause.javaClass.simpleName).append(": ").append(cause.message)
            Diag.log("rust", "failed: ${cause.message}")
            report.toString()
        }
    }

    /**
     * Compiles a .rs file into a **cdylib** for the graphics-window feature.
     *
     * Rust has no `main` in the C sense, so the program must export:
     *     #[no_mangle] pub extern "C" fn cp_main() -> i32
     * The C loader already falls back to `cp_main` when `main` is absent.
     *
     * `-L <linkdir> -l codepocket` resolves cp_open()/cp_pixel()/… against the staged copy of
     * libcodepocket.so (the same trick the C path needs, since nativeLibraryDir is empty).
     */
    suspend fun compileWindowProgram(context: Context, source: File): Result<File> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val compiler = ensureInstalled(context, { line -> Diag.log("rust", line.take(160)) })
                val work = File(context.filesDir, "rustbuild").apply { mkdirs() }

                val engine = com.dsh.codepocket.window.CpWindow.stageEngine(context)
                    ?: error("无法从 APK 中取出 libcodepocket.so")
                val linkDir = engine.parentFile ?: work

                val library = File(work, "lib${source.nameWithoutExtension}.so")
                if (library.exists()) library.delete()

                val output = exec(
                    context,
                    compiler,
                    listOf(
                        "--crate-type", "cdylib",
                        "-O",
                        "-o", library.absolutePath,
                        source.absolutePath,
                        "-L", linkDir.absolutePath,
                        "-l", "codepocket",
                    ),
                    work,
                )
                Diag.log("rust", "window build: ${output.replace('\n', ' ').take(300)}")
                if (!library.exists()) error("编译失败：$output")
                library
            }
        }
    }

    /** Used by the language manager: is rustc present, and how big is the prefix? */
    fun installedMb(context: Context): Double {
        val root = root(context)
        if (!root.exists()) return 0.0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024.0 / 1024.0
    }

    /** The demo program, for when the user has no .rs file yet. */
    fun demoSource() = HELLO
}
