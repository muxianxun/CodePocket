package com.dsh.codepocket

import com.dsh.codepocket.runtime.PackageCli
import com.dsh.codepocket.runtime.TermuxRepo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `pkg` routing and output text, checked against a fake host so no device, network or UI is
 * involved. (Same lesson as the .deb unpacker: pure logic verified by unit test beats
 * tap-the-screen verification.)
 */
class PackageCliTest {

    private fun pkg(name: String, size: Long = 1024L * 1024, deps: List<List<String>> = emptyList()) =
        TermuxRepo.Pkg(name, "1.0", "pool/main/$name/${name}_1.0_aarch64.deb", size, deps)

    private val index = mapOf(
        "lua" to pkg("lua", 300_000, listOf(listOf("readline"))),
        "luajit" to pkg("luajit", 500_000),
        "readline" to pkg("readline", 200_000),
        "clang" to pkg("clang", 30_000_000, listOf(listOf("libllvm"))),
        "libllvm" to pkg("libllvm", 30_000_000),
    )

    private inner class FakeHost(
        val installed: MutableSet<String> = mutableSetOf(),
        val indexFails: Boolean = false,
    ) : PackageCli.Host {
        val installCalls = mutableListOf<List<String>>()
        val removeCalls = mutableListOf<List<String>>()
        var refreshCount = 0

        override fun installed(): Set<String> = installed.toSet()

        override suspend fun index(refresh: Boolean): Result<Map<String, TermuxRepo.Pkg>> {
            if (refresh) refreshCount++
            return if (indexFails) Result.failure(RuntimeException("网络断了")) else Result.success(index)
        }

        override suspend fun install(names: List<String>, emit: (String) -> Unit): Result<List<String>> {
            installCalls += names
            names.forEach { emit("正在安装 $it …") }
            installed += names
            return Result.success(names)
        }

        override suspend fun remove(names: List<String>): Result<List<String>> {
            removeCalls += names
            val gone = names.filter { installed.remove(it) }
            return Result.success(gone)
        }
    }

    private fun run(vararg args: String, host: PackageCli.Host = FakeHost()): List<String> {
        val lines = mutableListOf<String>()
        runBlocking { PackageCli.handle(args.toList(), { lines += it }, host) }
        return lines
    }

    @Test
    fun bareInvocationPrintsHelp() {
        val out = run()
        assertTrue(out.first().startsWith("用法: pkg"))
        assertTrue(out.first().contains("install"))
    }

    @Test
    fun unknownCommandExplainsItself() {
        val out = run("frobnicate")
        assertTrue(out.first().contains("未知命令"))
    }

    @Test
    fun listReportsEmptyThenInstalledPackages() {
        val host = FakeHost()
        assertTrue(run("list", host = host).first().contains("尚未安装"))
        host.installed += setOf("lua", "readline")
        val out = run("list", host = host)
        assertTrue(out.first().contains("已安装 2 个包"))
        assertTrue(out[1].contains("lua"))
        assertTrue(out[1].contains("readline"))
    }

    @Test
    fun searchMatchesSubstringAndReportsCount() {
        val out = run("search", "lua")
        assertTrue("应同时命中 lua 与 luajit", out.first() == "匹配 2 个:")
        assertTrue(out.any { it.contains("luajit") })
    }

    @Test
    fun searchWithoutKeywordShowsUsage() {
        assertTrue(run("search").first().contains("用法"))
    }

    @Test
    fun searchWithNoHitsSaysSo() {
        val out = run("search", "zzz")
        assertTrue(out.first().contains("没有匹配"))
    }

    @Test
    fun infoShowsVersionSizeAndDependencies() {
        val out = run("info", "clang")
        assertEquals("clang 1.0", out[0])
        assertTrue(out.any { it.contains("下载大小") })
        assertTrue(out.any { it.contains("libllvm") })
    }

    @Test
    fun installPassesEveryNamedPackageThrough() {
        val host = FakeHost()
        val out = run("install", "lua", "readline", host = host)
        assertEquals(listOf(listOf("lua", "readline")), host.installCalls)
        assertTrue(out.any { it.startsWith("完成") })
    }

    @Test
    fun installWithNoArgumentsShowsUsage() {
        assertTrue(run("install").first().contains("用法"))
    }

    @Test
    fun removeReportsWhatActuallyWentAway() {
        val host = FakeHost(mutableSetOf("lua"))
        val out = run("remove", "lua", "notinstalled", host = host)
        assertTrue(out.first().contains("已删除: lua"))
        assertTrue("没装的包不应谎报删除", !out.first().contains("notinstalled"))
    }

    @Test
    fun updateRefreshesTheIndex() {
        val host = FakeHost()
        val out = run("update", host = host)
        assertEquals(1, host.refreshCount)
        assertTrue(out.first().contains("索引已更新"))
    }

    @Test
    fun indexFailureIsReportedNotThrown() {
        val out = run("search", "lua", host = FakeHost(indexFails = true))
        assertTrue(out.first().contains("索引获取失败"))
    }

    @Test
    fun splitArgsCollapsesWhitespace() {
        assertEquals(listOf("install", "lua"), PackageCli.splitArgs("  install   lua  "))
        assertEquals(emptyList<String>(), PackageCli.splitArgs("   "))
    }

    @Test
    fun shimScriptIsPosixAndSelfContained() {
        val script = PackageCli.shimScript(File("/data/data/com.dsh.codepocket/files/toolchains"))
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertTrue(script.contains("pkg.req"))
        assertTrue(script.contains("pkg.done"))
        // $* must survive into the script, not be expanded by Kotlin.
        assertTrue(script.contains("\"\$*\""))
    }
}
