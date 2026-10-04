package com.dsh.codepocket.python

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The embedded CPython runtime (Chaquopy).
 *
 * Python runs **inside the app process** rather than as a child process, which is what
 * makes it possible at all: Android 10+ refuses to exec() binaries stored in an app's
 * private data directory, so "unzip a python binary and run it" cannot work.
 */
object CodePython {

    @Volatile
    private var started = false

    @Volatile
    private var initError: String? = null

    val isAvailable: Boolean get() = initError == null

    private fun module(): PyObject? {
        if (initError != null) return null
        return try {
            val py = Python.getInstance()
            started = true
            py.getModule("script_runner")
        } catch (t: Throwable) {
            initError = "${t.javaClass.simpleName}: ${t.message}"
            Diag.log("python", "init failed: $initError")
            null
        }
    }

    suspend fun environment(): String = withContext(Dispatchers.IO) {
        val mod = module() ?: return@withContext "Python 不可用：${initError ?: "未初始化"}"
        try {
            val info = mod.callAttr("environment_info").toString()
            Diag.log("python", "environment: $info")
            info
        } catch (t: Throwable) {
            "读取 Python 版本失败：${t.message}"
        }
    }

    /** Runs a .py file and returns its captured stdout/stderr. */
    suspend fun runFile(path: String, argv: List<String> = emptyList()): String =
        withContext(Dispatchers.IO) {
            val mod = module() ?: return@withContext "Python 不可用：${initError ?: "未初始化"}"
            val started = System.currentTimeMillis()
            try {
                val output = mod.callAttr("run_file", path, argv.toTypedArray()).toString()
                Diag.log(
                    "python",
                    "run $path in ${System.currentTimeMillis() - started}ms, ${output.length} chars out",
                )
                output
            } catch (t: Throwable) {
                Diag.log("python", "run failed: ${t.javaClass.simpleName} ${t.message}")
                "运行失败：${t.javaClass.simpleName}: ${t.message}"
            }
        }

    /** Runs a code snippet (used for AI-generated code preview). */
    suspend fun runSource(code: String): String = withContext(Dispatchers.IO) {
        val mod = module() ?: return@withContext "Python 不可用：${initError ?: "未初始化"}"
        try {
            mod.callAttr("run_source", code).toString()
        } catch (t: Throwable) {
            "运行失败：${t.message}"
        }
    }
}
