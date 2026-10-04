package com.dsh.codepocket.runtime

import android.content.Context
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native (C/C++) support needs something the other languages do not: the compiler and the
 * compiled binary must both be **exec()'ed**, and Android 10+ blocks exec of files inside
 * an app's data directory unless the app targets API ≤ 28.
 *
 * This object answers that question with a real experiment instead of a guess: it copies a
 * genuine binary into the app's data directory, marks it executable and runs it in-process.
 * Whatever the OS decides is reported verbatim.
 */
object NativeToolchain {

    /** ABI names of what Termux publishes and what this device can run. */
    fun termuxArch(): String = when {
        android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
        android.os.Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "arm"
        else -> "x86_64"
    }

    suspend fun execCapability(context: Context): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()

        // NOTE: an app normally CANNOT read /sys/fs/selinux/enforce, so a failed read must be
        // reported as "unknown" — never as "Permissive". Conflating the two produced a wrong
        // label on a device that was actually Enforcing (caught by cross-checking getenforce).
        val enforceRaw = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()
        val selinuxLabel = when (enforceRaw) {
            "1" -> "强制 Enforcing"
            "0" -> "宽容 Permissive"
            null -> "未知（App 域无权读该节点，属正常；以 adb getenforce 为准）"
            else -> "未知（节点值=$enforceRaw）"
        }
        out.append("SELinux: ").append(selinuxLabel)
            .append("  ·  targetSdk=")
            .append(context.applicationInfo.targetSdkVersion)
            .append('\n')
        out.append("设备 ABI: ").append(android.os.Build.SUPPORTED_ABIS.joinToString(","))
            .append("  ·  工具链架构: ").append(termuxArch()).append('\n')

        val dir = File(context.filesDir, "execprobe").apply { mkdirs() }
        val source = File("/system/bin/toybox")
        if (!source.exists()) {
            out.append("找不到 /system/bin/toybox，无法完成探测\n")
            return@withContext out.toString()
        }
        // Named after a toybox applet so the copied binary actually dispatches.
        val probe = File(dir, "echo")
        runCatching { source.copyTo(probe, overwrite = true) }
            .onFailure { out.append("复制探针失败: ${it.message}\n"); return@withContext out.toString() }
        probe.setExecutable(true, false)
        out.append("探针: 已把一个真二进制放进数据目录（${probe.length()} B）\n")

        try {
            val process = ProcessBuilder(probe.absolutePath, "EXEC_FROM_APPDATA_OK")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            out.append("App 身份执行 → 退出码=").append(code).append("  输出=").append(text).append('\n')
            if (code == 0 && text.contains("EXEC_FROM_APPDATA_OK")) {
                out.append("结论：✅ 可以执行数据目录里的二进制，C/C++ 能走原生编译")
            } else {
                out.append("结论：❌ 执行被限制（需 Shizuku 代执行，或降低 targetSdk）")
            }
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            out.append("App 身份执行失败: ")
                .append(cause.javaClass.simpleName).append(": ").append(cause.message).append('\n')
            out.append("结论：❌ 执行被限制（需 Shizuku 代执行，或降低 targetSdk）")
        }

        Diag.log("native", "exec probe: " + out.toString().replace('\n', ' '))

        // Same button also verifies the package-unpacking chain, using a .deb the caller
        // dropped into the app's external files dir.
        out.append("\n--- 解包自检 ---\n")
        out.append(ToolchainInstaller.extractProbe(context))

        out.toString()
    }

    /**
     * Downloads a Termux toolchain package and unpacks it under files/toolchains/.
     * Termux debs are `ar` archives holding data.tar.xz — xz is not in java.util.zip, hence
     * the org.tukaani:xz dependency.
     */
    suspend fun installPackage(
        context: Context,
        packageUrl: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "toolchains")
        val cache = File(root, "cache").apply { mkdirs() }
        val deb = File(cache, packageUrl.substringAfterLast('/'))
        Downloader.download(packageUrl, deb, onProgress).mapCatching { file ->
            Diag.log("native", "downloaded ${file.name} (${file.length() / 1024} KB)")
            file
        }
    }
}
