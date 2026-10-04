package com.dsh.codepocket.runtime

import android.content.Context
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Installs the Termux clang toolchain on demand and uses it to compile and run C and C++.
 *
 * Whole flow: resolve the dependency closure from the Termux index (~15 packages, ~83 MB for
 * aarch64) -> download each .deb -> unpack them into one shared prefix -> run clang with a
 * loader path pointing at that prefix -> build and execute a real program.
 *
 * Progress callbacks are invoked on the **caller's** dispatcher (the UI calls this from a
 * main-dispatcher scope), while the heavy file/network work happens on Dispatchers.IO.
 */
object ClangToolchain {

    private const val HELLO_C = """
#include <stdio.h>

int main(void) {
    printf("C_ON_ANDROID_OK\n");
    printf("compiler=%s\n", __VERSION__);
    int sum = 0;
    for (int i = 1; i <= 10; i++) sum += i;
    printf("sum(1..10)=%d\n", sum);
    return 0;
}
"""

    private const val HELLO_CPP = """
#include <iostream>
#include <vector>
#include <numeric>

int main() {
    std::vector<int> values{1, 2, 3, 4, 5};
    std::cout << "CPP_ON_ANDROID_OK" << std::endl;
    std::cout << "sum=" << std::accumulate(values.begin(), values.end(), 0) << std::endl;
    return 0;
}
"""

    fun root(context: Context) = File(context.filesDir, "toolchains/root")

    private fun usr(context: Context) = File(root(context), "usr")

    /** Termux ships several clang entry points; take whichever actually landed. */
    private fun findClang(context: Context, name: String): File? {
        val bin = File(usr(context), "bin")
        val direct = File(bin, name)
        if (direct.exists()) return direct
        return bin.listFiles()
            ?.filter { it.name.startsWith(name) && it.isFile }
            ?.maxByOrNull { it.name }
    }

    private fun environment(context: Context): Map<String, String> {
        val usr = usr(context)
        val tmp = File(usr, "tmp").apply { mkdirs() }
        val home = File(root(context), "home").apply { mkdirs() }
        return mapOf(
            "PATH" to "${usr.path}/bin:/system/bin:/system/xbin",
            // Termux binaries carry an RPATH pointing at the original Termux prefix, so the
            // real library location has to be supplied here.
            "LD_LIBRARY_PATH" to "${usr.path}/lib",
            "PREFIX" to usr.path,
            "TMPDIR" to tmp.path,
            "HOME" to home.path,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
        )
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0)

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

    suspend fun installAndRun(context: Context, onProgress: (String) -> Unit): String {
        val report = StringBuilder()
        val arch = NativeToolchain.termuxArch()
        val root = root(context).apply { mkdirs() }
        val cache = File(context.filesDir, "toolchains/cache").apply { mkdirs() }
        val work = File(context.filesDir, "cbuild").apply { mkdirs() }

        // ---- 1) index + plan ----
        onProgress("拉取 Termux 索引 ($arch)…")
        val indexFile = File(cache, "Packages-$arch")
        val index = try {
            withContext(Dispatchers.IO) {
                Downloader.download(TermuxRepo.indexUrl(arch), indexFile).getOrThrow()
                TermuxRepo.parseIndex(indexFile.readText())
            }
        } catch (t: Throwable) {
            return "索引下载失败: ${t.message}"
        }
        val plan = TermuxRepo.resolve("clang", index)
        if (plan.packages.isEmpty()) return "索引里找不到 clang"
        report.append("计划: ").append(plan.packages.size).append(" 个包, ")
            .append(mb(plan.totalBytes)).append(" MB\n")
        if (plan.missing.isNotEmpty()) {
            report.append("缺失依赖: ").append(plan.missing.joinToString(", ")).append('\n')
        }

        // ---- 2) download + unpack (largest first, so failures surface early) ----
        var completed = 0L
        for (pkg in plan.packages.sortedByDescending { it.sizeBytes }) {
            onProgress(
                "下载 ${pkg.name} (${completed * 100 / maxOf(1L, plan.totalBytes)}%, " +
                    "${mb(completed)}/${mb(plan.totalBytes)} MB)…",
            )
            try {
                withContext(Dispatchers.IO) {
                    val deb = File(cache, pkg.filename.substringAfterLast('/'))
                    if (!deb.exists() || deb.length() != pkg.sizeBytes) {
                        Downloader.download(TermuxRepo.packageUrl(pkg.filename), deb).getOrThrow()
                    }
                    val result = ToolchainInstaller.extractDeb(deb, root)
                    Diag.log("clang", "unpacked ${pkg.name}: ${result.files} files, ${result.errors.size} errors")
                }
            } catch (t: Throwable) {
                var cause: Throwable = t
                while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
                report.append("❌ ").append(pkg.name).append(" 失败: ")
                    .append(cause.javaClass.simpleName).append(": ").append(cause.message).append('\n')
                return report.toString()
            }
            completed += pkg.sizeBytes
        }
        report.append("解包完成 ").append(mb(plan.totalBytes)).append(" MB\n")

        // ---- 3) compile and run C, then C++ ----
        val clang = withContext(Dispatchers.IO) { findClang(context, "clang") }
        if (clang == null) {
            report.append("❌ 没找到 clang 可执行文件；usr/bin 内容: ")
            report.append(
                withContext(Dispatchers.IO) {
                    File(usr(context), "bin").listFiles()?.take(12)?.joinToString(", ") { it.name } ?: "（空）"
                },
            )
            Diag.log("clang", report.toString().replace('\n', ' '))
            return report.toString()
        }
        if (!clang.canExecute()) clang.setExecutable(true, false)
        report.append("clang: ").append(clang.relativeTo(root).path)
            .append(" (").append(clang.length() / 1024).append(" KB)\n")

        val cSource = File(work, "hello.c").apply { writeText(HELLO_C) }
        val cOut = File(work, "hello_c")
        onProgress("编译 hello.c…")
        report.append("[C 编译] ").append(
            withContext(Dispatchers.IO) {
                exec(context, clang, listOf("-O0", "-o", cOut.absolutePath, cSource.absolutePath), work)
            }.trim().take(500),
        ).append('\n')
        if (cOut.exists()) {
            report.append("[C 运行] ").append(
                withContext(Dispatchers.IO) { exec(context, cOut, emptyList(), work) }.trim(),
            ).append('\n')
        }

        val cpp = withContext(Dispatchers.IO) { findClang(context, "clang++") }
        if (cpp != null) {
            val cppSource = File(work, "hello.cpp").apply { writeText(HELLO_CPP) }
            val cppOut = File(work, "hello_cpp")
            onProgress("编译 hello.cpp…")
            report.append("[C++ 编译] ").append(
                withContext(Dispatchers.IO) {
                    exec(context, cpp, listOf("-O0", "-o", cppOut.absolutePath, cppSource.absolutePath), work)
                }.trim().take(500),
            ).append('\n')
            if (cppOut.exists()) {
                report.append("[C++ 运行] ").append(
                    withContext(Dispatchers.IO) { exec(context, cppOut, emptyList(), work) }.trim(),
                ).append('\n')
            }
        } else {
            report.append("（未找到 clang++）\n")
        }

        Diag.log("clang", report.toString().replace('\n', ' ').take(900))
        return report.toString()
    }

    /**
     * Compiles a source file into a **shared library** instead of an executable.
     *
     * This is what the graphics-window feature needs: Android has no display server, so a
     * plain process cannot own a window. A library can be loaded into the app — where a
     * Surface exists — and its `main()` then draws into a SurfaceView.
     *
     * The window API header is shipped from assets so user code can simply
     * `#include <codepocket.h>` with no manual setup.
     */
    suspend fun compileWindowProgram(context: Context, source: File): Result<File> {
        val driver = if (source.extension.lowercase() == "c") "clang" else "clang++"
        return withContext(Dispatchers.IO) {
            runCatching {
                val compiler = findClang(context, driver)
                    ?: error("未找到 $driver，请先在「语言」页下载 clang 工具链")
                val work = File(context.filesDir, "cbuild").apply { mkdirs() }
                val includeDir = File(work, "include").apply { mkdirs() }
                val header = File(includeDir, "codepocket.h")
                context.assets.open("include/codepocket.h").use { input ->
                    header.outputStream().use { output -> input.copyTo(output) }
                }

                val library = File(work, "lib${source.nameWithoutExtension}.so")
                if (library.exists()) library.delete()

                // Link against libcodepocket.so so cp_open()/cp_pixel()/… resolve.
                //
                // Do NOT point -L at applicationInfo.nativeLibraryDir: with the modern
                // default extractNativeLibs=false the native libraries are never unpacked
                // there, that directory is empty, and the link dies with
                // "ld.lld: error: unable to find library -lcodepocket" (observed on device).
                // Instead pull the .so straight out of the APK we are running from.
                val linkDir = File(work, "linklibs").apply { mkdirs() }
                val engineLib = File(linkDir, "libcodepocket.so")
                if (!engineLib.exists() || engineLib.length() == 0L) {
                    java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                        val entry = zip.entries().asSequence()
                            .firstOrNull { it.name.endsWith("libcodepocket.so") }
                            ?: error("APK 里找不到 libcodepocket.so")
                        zip.getInputStream(entry).use { input ->
                            engineLib.outputStream().use { output -> input.copyTo(output) }
                        }
                        Diag.log("cpwin", "staged ${entry.name} -> ${engineLib.length()} B")
                    }
                }

                val output = exec(
                    context,
                    compiler,
                    listOf(
                        "-shared", "-fPIC", "-O0",
                        "-I", includeDir.absolutePath,
                        "-o", library.absolutePath,
                        source.absolutePath,
                        "-L", linkDir.absolutePath,
                        "-lcodepocket",
                    ),
                    work,
                )
                Diag.log("cpwin", "window build: ${output.replace('\n', ' ').take(300)}")
                if (!library.exists()) error("编译失败：$output")
                library
            }
        }
    }

    /**
     * Compiles and runs one source file with the already-installed toolchain.
     * Used by the editor; returns the compiler output and the program output together.
     */
    suspend fun compileAndRunSource(context: Context, source: File): String {
        val ext = source.extension.lowercase()
        val driver = if (ext == "c") "clang" else "clang++"
        val compiler = withContext(Dispatchers.IO) { findClang(context, driver) }
            ?: return "未找到 $driver。请先在「语言」页下载 clang 工具链。"

        // The working directory stays next to the source so relative paths behave the way the
        // user expects, but the EXECUTABLE must live in internal storage: the shared/external
        // filesystem is mounted noexec, and building next to the source produced
        // "IOException: error=13, Permission denied" the moment the binary was launched.
        val work = source.parentFile ?: File(context.filesDir, "cbuild").apply { mkdirs() }
        val binDir = File(context.filesDir, "cbuild").apply { mkdirs() }
        val executable = File(binDir, source.nameWithoutExtension + "_bin")
        val report = StringBuilder()

        val compile = withContext(Dispatchers.IO) {
            exec(context, compiler, listOf("-O0", "-o", executable.absolutePath, source.absolutePath), work)
        }
        report.append("[编译 ").append(driver).append("] ").append(compile.trim().take(700)).append('\n')

        if (!executable.exists()) {
            report.append("❌ 编译未产出可执行文件")
            Diag.log("clang", "compile failed for ${source.name}: ${compile.take(300)}")
            return report.toString()
        }
        if (!executable.canExecute()) executable.setExecutable(true, false)
        val run = withContext(Dispatchers.IO) { exec(context, executable, emptyList(), work) }
        report.append("[运行] ").append(run.trim())
        Diag.log("clang", "built and ran ${source.name}: ${run.take(200)}")
        return report.toString()
    }

    /** Size of what is already on disk, for the UI. */
    fun installedMb(context: Context): Double {
        val root = root(context)
        if (!root.exists()) return 0.0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024.0 / 1024.0
    }
}
