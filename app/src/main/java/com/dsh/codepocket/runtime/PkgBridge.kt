package com.dsh.codepocket.runtime

import android.content.Context
import com.dsh.codepocket.Diag
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Connects the real package machinery to the `pkg` command, using the file protocol that
 * [PackageCli] documents.
 *
 * A daemon thread polls `pkg.req` (dropped there by the `pkg` shim script, which runs in the
 * PTY as the app's own uid), performs the work and streams output into `pkg.res`. Only a
 * plain thread is used: it must outlive any screen and never touch UI state.
 */
object PkgBridge {

    private const val REQ = "pkg.req"
    private const val RES = "pkg.res"
    private const val DONE = "pkg.done"
    private const val INSTALLED = "installed.txt"

    @Volatile
    private var started = false

    fun stateDir(context: Context) = File(context.filesDir, "toolchains").apply { mkdirs() }

    fun prefix(context: Context) = File(ClangToolchain.root(context), "usr")

    /**
     * Writes (or refreshes) the `pkg` script inside the toolchain prefix and makes it
     * executable, so an interactive shell can find it on PATH.
     */
    fun ensureShim(context: Context): File {
        val bin = File(prefix(context), "bin").apply { mkdirs() }
        val shim = File(bin, "pkg")
        val wanted = PackageCli.shimScript(stateDir(context))
        if (!shim.exists() || shim.readText() != wanted) {
            shim.writeText(wanted)
            Diag.log("pkg", "shim written: ${shim.absolutePath}")
        }
        shim.setExecutable(true, false)
        return shim
    }

    /** Environment lines the terminal should export so `pkg` and installed tools are usable. */
    fun shellExports(context: Context): String {
        val usr = prefix(context)
        val state = stateDir(context)
        return buildString {
            append("export PREFIX=").append(usr.absolutePath).append('\n')
            append("export PATH=").append(usr.absolutePath).append("/bin:/system/bin:/system/xbin\n")
            append("export LD_LIBRARY_PATH=").append(usr.absolutePath).append("/lib\n")
            append("export TMPDIR=").append(usr.absolutePath).append("/tmp\n")
            append("export CODEPOCKET_STATE=").append(state.absolutePath).append('\n')
        }
    }

    fun startWatcher(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread {
            val state = stateDir(app)
            Diag.log("pkg", "watcher started in ${state.absolutePath}")
            while (true) {
                try {
                    val req = File(state, REQ)
                    if (req.exists() && req.length() > 0) {
                        val line = runCatching { req.readText().trim() }.getOrDefault("")
                        req.delete()
                        val res = File(state, RES)
                        res.writeText("")
                        File(state, DONE).delete()
                        Diag.log("pkg", "request: $line")
                        try {
                            runBlocking {
                                PackageCli.handle(
                                    PackageCli.splitArgs(line),
                                    { text -> runCatching { res.appendText(text + "\n") } },
                                    Host(app),
                                )
                            }
                        } catch (t: Throwable) {
                            runCatching { res.appendText("pkg: 内部错误 ${t.javaClass.simpleName}: ${t.message}\n") }
                            Diag.log("pkg", "handler failed: ${t.message}")
                        }
                        File(state, DONE).writeText("done")
                    }
                } catch (t: Throwable) {
                    Diag.log("pkg", "watcher loop error: ${t.message}")
                }
                Thread.sleep(300)
            }
        }.apply {
            isDaemon = true
            name = "pkg-watcher"
        }.start()
    }

    /** The real implementation of the CLI's host interface. */
    private class Host(private val context: Context) : PackageCli.Host {

        @Volatile
        private var cached: Map<String, TermuxRepo.Pkg>? = null

        private val record get() = File(stateDir(context), INSTALLED)

        override fun installed(): Set<String> =
            record.takeIf { it.exists() }
                ?.readLines()
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()

        private fun remember(names: Collection<String>) {
            val merged = (installed() + names).sorted()
            record.writeText(merged.joinToString("\n") + "\n")
        }

        private fun forget(names: Collection<String>) {
            record.writeText((installed() - names.toSet()).sorted().joinToString("\n") + "\n")
        }

        override suspend fun index(refresh: Boolean): Result<Map<String, TermuxRepo.Pkg>> {
            cached?.takeIf { !refresh }?.let { return Result.success(it) }
            return runCatching {
                val arch = NativeToolchain.termuxArch()
                val file = File(stateDir(context), "Packages-$arch")
                if (refresh || !file.exists()) {
                    Downloader.download(TermuxRepo.indexUrl(arch), file).getOrThrow()
                }
                TermuxRepo.parseIndex(file.readText()).also { cached = it }
            }
        }

        override suspend fun install(names: List<String>, emit: (String) -> Unit): Result<List<String>> =
            runCatching {
                val index = index(false).getOrThrow()
                val root = ClangToolchain.root(context)
                val cache = File(stateDir(context), "cache").apply { mkdirs() }
                val already = installed()
                val done = mutableListOf<String>()

                for (name in names) {
                    val plan = TermuxRepo.resolve(name, index)
                    if (plan.packages.isEmpty()) {
                        emit("找不到包: $name")
                        continue
                    }
                    if (plan.missing.isNotEmpty()) {
                        emit("$name: 有 ${plan.missing.size} 个依赖无法解析（${plan.missing.take(3).joinToString()}），跳过安装")
                        continue
                    }
                    val needed = plan.packages.filter { it.name !in already }
                    emit("$name: 共 ${plan.packages.size} 个包，需下载 ${needed.size} 个（${PackageCli.mb(needed.sumOf { it.sizeBytes })}）")

                    for (pkg in needed.sortedByDescending { it.sizeBytes }) {
                        emit("  下载 ${pkg.name} ${PackageCli.mb(pkg.sizeBytes)} …")
                        val deb = File(cache, pkg.filename.substringAfterLast('/'))
                        if (!deb.exists() || deb.length() != pkg.sizeBytes) {
                            Downloader.download(TermuxRepo.packageUrl(pkg.filename), deb).getOrThrow()
                        }
                        val unpacked = ToolchainInstaller.extractDeb(deb, root)
                        if (unpacked.errors.isNotEmpty()) {
                            emit("  ⚠ ${pkg.name}: ${unpacked.errors.first()}")
                        }
                        emit("  解开 ${pkg.name}: ${unpacked.files} 个文件")
                    }
                    remember(plan.packages.map { it.name })
                    done += name
                    emit("$name 安装完成")
                }
                done
            }

        override suspend fun remove(names: List<String>): Result<List<String>> = runCatching {
            // Removing individual files needs a per-package manifest, which installing does
            // not record yet. Rather than deleting guesses, this only drops the package from
            // the installed list and says so.
            val gone = names.filter { it in installed() }
            forget(gone)
            if (gone.isNotEmpty()) {
                emitRemoveNote()
            }
            gone
        }

        private fun emitRemoveNote() {
            Diag.log("pkg", "remove: files left in place (no manifest)")
        }
    }
}
