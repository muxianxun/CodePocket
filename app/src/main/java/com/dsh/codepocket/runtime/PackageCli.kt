package com.dsh.codepocket.runtime

import java.io.File
import java.util.Locale

/**
 * The `pkg` command surface, split from its I/O so the routing and text can be unit tested.
 *
 * ## Why a file protocol instead of intercepting keystrokes
 *
 * The terminal is a real PTY running `/system/bin/sh`, and that shell runs as the **same uid
 * as the app**, so it can read and write the app's private directory. Rather than hijacking
 * line editing (which would break pipes, redirection, history and everything else a shell
 * does), `pkg` is a small shell script that:
 *
 *   1. writes its arguments to `pkg.req`
 *   2. waits for `pkg.done`, printing `pkg.res` whenever it appears and truncating it
 *
 * A long-lived coroutine in the app watches for `pkg.req`, runs the real work (index lookup,
 * dependency closure, download, unpack) and appends human-readable output to `pkg.res`.
 *
 * The alternative — running a CLI via `app_process` — cannot work: that process runs as the
 * shell uid and is therefore unable to write into the app's private data directory.
 */
object PackageCli {

    /** Everything the CLI needs from the app; injected so tests can fake it. */
    interface Host {
        /** Installed package names, read from the unpacked prefix. */
        fun installed(): Set<String>

        /** The parsed Termux index, fetching it once if necessary. */
        suspend fun index(refresh: Boolean = false): Result<Map<String, TermuxRepo.Pkg>>

        /** Resolve + download + unpack. Emits progress lines. Returns the installed names. */
        suspend fun install(names: List<String>, emit: (String) -> Unit): Result<List<String>>

        /** Delete the files a package owns. Returns how many packages went away. */
        suspend fun remove(names: List<String>): Result<List<String>>
    }

    const val HELP = """用法: pkg <命令> [参数]
命令:
  list              列出已安装的包
  search <关键字>    在 Termux 索引里搜索
  info <包名>        查看版本 / 大小 / 依赖
  install <包名…>    安装（自动解析并安装依赖）
  remove <包名…>     卸载
  update            重新拉取索引
  help              显示本帮助

例: pkg search lua   ·   pkg install lua   ·   pkg list"""

    /**
     * @param args the words after `pkg` (empty for a bare invocation)
     * @param emit one already-formatted line (no trailing newline required)
     */
    suspend fun handle(args: List<String>, emit: (String) -> Unit, host: Host) {
        val command = args.firstOrNull()?.lowercase() ?: "help"
        val rest = args.drop(1)

        when (command) {
            "help", "--help", "-h" -> emit(HELP)

            "list" -> {
                val installed = host.installed().sorted()
                if (installed.isEmpty()) {
                    emit("尚未安装任何包。（工具链自带的 clang 不算包）")
                } else {
                    emit("已安装 ${installed.size} 个包:")
                    installed.chunked(4).forEach { emit("  " + it.joinToString("  ")) }
                }
            }

            "search" -> {
                val keyword = rest.joinToString(" ").trim()
                if (keyword.isEmpty()) {
                    emit("用法: pkg search <关键字>")
                    return
                }
                host.index().fold(
                    onSuccess = { index ->
                        val hits = index.values
                            .filter { it.name.contains(keyword, ignoreCase = true) }
                            .sortedBy { it.name }
                            .take(30)
                        if (hits.isEmpty()) {
                            emit("没有匹配「$keyword」的包（索引共 ${index.size} 个）")
                        } else {
                            emit("匹配 ${hits.size} 个:")
                            hits.forEach {
                                emit("  ${it.name.padEnd(28)} ${mb(it.sizeBytes)}")
                            }
                        }
                    },
                    onFailure = { emit("索引获取失败: ${it.message}") },
                )
            }

            "info" -> {
                val name = rest.firstOrNull()
                if (name == null) {
                    emit("用法: pkg info <包名>")
                    return
                }
                host.index().fold(
                    onSuccess = { index ->
                        val pkg = index[name]
                        if (pkg == null) {
                            emit("索引里没有 $name")
                        } else {
                            emit("$name ${pkg.version}")
                            emit("  下载大小: ${mb(pkg.sizeBytes)}")
                            emit("  依赖: " + (pkg.depends.joinToString(", ") { it.first() }.ifEmpty { "（无）" }))
                            emit("  ${TermuxRepo.packageUrl(pkg.filename)}")
                        }
                    },
                    onFailure = { emit("索引获取失败: ${it.message}") },
                )
            }

            "install", "add" -> {
                val names = rest.filter { it.isNotBlank() }
                if (names.isEmpty()) {
                    emit("用法: pkg install <包名…>")
                    return
                }
                host.install(names, emit).fold(
                    onSuccess = { done ->
                        emit(if (done.isEmpty()) "没有安装任何包" else "完成: ${done.joinToString(", ")}")
                    },
                    onFailure = { emit("安装失败: ${it.message}") },
                )
            }

            "remove", "uninstall", "rm" -> {
                val names = rest.filter { it.isNotBlank() }
                if (names.isEmpty()) {
                    emit("用法: pkg remove <包名…>")
                    return
                }
                host.remove(names).fold(
                    onSuccess = { gone -> emit(if (gone.isEmpty()) "没有匹配的包" else "已删除: ${gone.joinToString(", ")}") },
                    onFailure = { emit("卸载失败: ${it.message}") },
                )
            }

            "update", "refresh" -> {
                host.index(refresh = true).fold(
                    onSuccess = { emit("索引已更新: ${it.size} 个包") },
                    onFailure = { emit("索引更新失败: ${it.message}") },
                )
            }

            else -> emit("pkg: 未知命令「$command」\n\n$HELP")
        }
    }

    /** Splits a request line the way a shell would for simple, unquoted arguments. */
    fun splitArgs(line: String): List<String> =
        line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

    // ---------------------------------------------------------------- shim

    /**
     * The `pkg` script placed in the toolchain prefix. Chunked on purpose: the app appends to
     * `pkg.res` while it works and the loop drains it, so long downloads show progress
     * instead of a frozen prompt.
     */
    fun shimScript(stateDir: File): String = """
        #!/system/bin/sh
        # Generated by CodePocket. Talks to the app through files because the shell runs as
        # the app's uid and can therefore reach its private directory.
        STATE="${stateDir.absolutePath}"
        REQ="${'$'}STATE/pkg.req"
        RES="${'$'}STATE/pkg.res"
        DONE="${'$'}STATE/pkg.done"
        rm -f "${'$'}RES" "${'$'}DONE"
        printf '%s\n' "${'$'}*" > "${'$'}REQ"
        i=0
        while [ ! -f "${'$'}DONE" ]; do
          if [ -s "${'$'}RES" ]; then
            cat "${'$'}RES"
            : > "${'$'}RES"
          fi
          i=${'$'}((i + 1))
          if [ "${'$'}i" -gt 2400 ]; then
            echo "pkg: 等待 App 响应超时（App 是否在运行？）"
            rm -f "${'$'}REQ"
            exit 1
          fi
          sleep 0.5
        done
        if [ -s "${'$'}RES" ]; then cat "${'$'}RES"; fi
        rm -f "${'$'}DONE" "${'$'}REQ" "${'$'}RES"
    """.trimIndent() + "\n"
}
