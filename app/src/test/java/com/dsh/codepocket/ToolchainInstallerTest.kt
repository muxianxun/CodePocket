package com.dsh.codepocket

import com.dsh.codepocket.runtime.ToolchainInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the hand-written .deb unpacker against a real Termux package
 * (`termux-elf-cleaner_3.0.1-1_aarch64.deb`, 18 KB, in test resources).
 *
 * This runs on the JVM, so the ar + xz + tar parsing is checked deterministically without
 * relying on device UI automation. Cross-checked independently with Python's tarfile/lzma.
 */
class ToolchainInstallerTest {

    private fun probeDeb(): File {
        val url = javaClass.classLoader!!.getResource("probe.deb")
            ?: error("test resource probe.deb 缺失")
        return File(url.toURI())
    }

    @Test
    fun unpacksArXzTarChainAndKeepsStructure() {
        val dest = File(System.getProperty("java.io.tmpdir"), "cp_deb_${System.nanoTime()}")
        try {
            val result = ToolchainInstaller.extractDeb(probeDeb(), dest)

            val allPaths = dest.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(dest).path.replace(File.separatorChar, '/') }
                .sorted()
                .toList()
            println("解出文件数=${result.files} 磁盘实际=${allPaths.size}")
            println("路径: " + allPaths.joinToString(", "))
            println("错误: " + result.errors.joinToString("; "))

            assertEquals("不应有解析错误", emptyList<String>(), result.errors)
            assertTrue("应至少解出一个文件", result.files > 0)
            assertEquals("报告数与磁盘文件数应一致", allPaths.size, result.files)

            // Termux payloads live under ./data/data/com.termux/files/usr/... and the prefix
            // must be stripped, not kept.
            val binary = allPaths.firstOrNull { it.endsWith("usr/bin/termux-elf-cleaner") }
            assertTrue(
                "应解出 usr/bin/termux-elf-cleaner，实际路径: $allPaths",
                binary != null,
            )
            assertTrue(
                "不应把 Termux 前缀原样保留: $allPaths",
                allPaths.none { it.startsWith("data/data/com.termux") },
            )
            assertTrue(
                "解出的二进制不应是空文件",
                File(dest, binary!!).length() > 0,
            )
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonArInput() {
        val bogus = File.createTempFile("notadeb", ".deb")
        bogus.writeText("this is not an ar archive at all")
        val dest = File(System.getProperty("java.io.tmpdir"), "cp_bad_${System.nanoTime()}")
        try {
            var threw = false
            try {
                ToolchainInstaller.extractDeb(bogus, dest)
            } catch (t: Throwable) {
                threw = true
            }
            assertTrue("非 ar 输入应被拒绝", threw)
        } finally {
            bogus.delete()
            dest.deleteRecursively()
        }
    }
}
