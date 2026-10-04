package com.dsh.codepocket.editor

import android.content.Context
import com.dsh.codepocket.Diag
import com.dsh.codepocket.python.CodePython
import com.dsh.codepocket.runtime.ClangToolchain
import com.dsh.codepocket.runtime.JavaPipeline
import com.dsh.codepocket.runtime.RustToolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for "run this file", so the editor does not need to know how each
 * language is wired.
 *
 * The three mechanisms are genuinely different and worth keeping straight:
 *  - Python: CPython embedded in the app process (Chaquopy), no exec.
 *  - Java:   compiled in-process (Janino -> D8), executed by the system dalvikvm.
 *  - C/C++:  compiled by a downloaded clang, which — like its output — must be exec()'ed
 *            from app data, allowed because the app targets API 28.
 *
 * Lives in the editor package so the screen can use it without an extra import.
 */
object RunDispatcher {

    private val PYTHON = setOf("py")
    private val JAVA = setOf("java")
    private val NATIVE = setOf("c", "cpp", "cc", "cxx")
    private val RUST = setOf("rs")

    fun supports(ext: String): Boolean =
        ext in PYTHON || ext in JAVA || ext in NATIVE || ext in RUST

    /** Only Python can be interpreted directly; everything else needs a compile step. */
    fun buttonLabel(ext: String): String = when {
        ext in PYTHON -> "运行"
        supports(ext) -> "编译并运行"
        else -> "运行"
    }

    suspend fun run(context: Context, source: File, ext: String): String {
        val started = System.currentTimeMillis()
        val output = try {
            when (ext) {
                in PYTHON -> CodePython.runFile(source.absolutePath)
                in JAVA -> withContext(Dispatchers.IO) {
                    JavaPipeline.compileAndRun(context.filesDir, source)
                }
                in RUST -> RustToolchain.compileAndRunSource(context, source)
                in NATIVE -> ClangToolchain.compileAndRunSource(context, source)
                else -> "不支持的文件类型: .$ext"
            }
        } catch (t: Throwable) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            "运行失败: ${cause.javaClass.simpleName}: ${cause.message}"
        }
        Diag.log(
            "run",
            "$source (.$ext) in ${System.currentTimeMillis() - started}ms, ${output.length} chars",
        )
        return output
    }
}
