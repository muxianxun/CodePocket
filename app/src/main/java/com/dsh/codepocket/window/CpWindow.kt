package com.dsh.codepocket.window

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.codepocket.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android half of the C/C++ window feature.
 *
 * A program compiled with `#include <codepocket.h>` is a **shared library**, not an
 * executable: Android has no display server, so a window can only come from an Activity's
 * Surface. This composable supplies that Surface and hands it to the native side
 * (`cpwin.c`), then runs the user's `main()` on a background thread. The drawing calls in
 * the user's code land straight in this SurfaceView.
 *
 * The `cp_*` symbols live in libcodepocket.so, which the app already loads for the
 * terminal, so the user's library resolves them without any extra helper .so.
 */
object CpWindow {

    init {
        runCatching { System.loadLibrary("codepocket") }
            .onFailure { Diag.log("cpwin", "loadLibrary failed: ${it.message}") }
    }

    /** Declared as instance methods; the JNI side ignores the receiver argument. */
    external fun nativeAttachSurface(surface: android.view.Surface?)

    external fun nativeDetachSurface()

    external fun nativePushKey(code: Int)

    external fun nativeRequestClose()

    /**
     * dlopen()s the compiled library inside the app process and calls its `main`
     * (or `cp_main`). Returns the program's return value, negative on failure.
     */
    external fun nativeRunSharedLibrary(path: String): Int

    /**
     * Extracts libcodepocket.so out of the running APK into a stable directory and records
     * its absolute path.
     *
     * Two reasons this exists: (1) with the modern default `extractNativeLibs=false` the
     * libraries are never unpacked into nativeLibraryDir, so anything that needs a real file
     * (the linker, or ctypes from Python) has nothing to point at; (2) Python's ctypes can
     * then load it by path instead of guessing a SONAME.
     */
    fun stageEngine(context: android.content.Context): File? = runCatching {
        val dir = File(context.filesDir, "cbuild/linklibs").apply { mkdirs() }
        val target = File(dir, "libcodepocket.so")
        if (!target.exists() || target.length() == 0L) {
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name.endsWith("libcodepocket.so") }
                    ?: error("APK 里找不到 libcodepocket.so")
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        File(dir, "engine_path.txt").writeText(target.absolutePath)
        Diag.log("cpwin", "engine staged: ${target.absolutePath} (${target.length()} B)")
        target
    }.onFailure { Diag.log("cpwin", "stageEngine failed: ${it.message}") }.getOrNull()
}

/**
 * Full-screen host for a program that draws into a window, whatever language it is written
 * in: C/C++ arrives as a compiled shared library, Python as a script that talks to the same
 * native API through ctypes. Both need this Surface, and `cp_open` waits for it.
 */
@Composable
fun CpWindowScreen(
    title: String,
    runProgram: suspend () -> String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("载入中…") }
    var output by remember { mutableStateOf("") }

    LaunchedEffect(title) {
        status = "运行中 · $title"
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching { runProgram() }.getOrElse { "运行失败：${it.message}" }
        }
        val elapsed = System.currentTimeMillis() - started
        status = "已结束（${elapsed}ms）"
        output = result
        // Log the whole thing: Python prints the actual exception LAST, so a leading
        // truncation would throw away the only useful line.
        Diag.log("cpwin", "program finished in ${elapsed}ms, ${result.length} chars:")
        Diag.log("cpwin", result.take(4000))
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                // Log both outcomes: a missing log line made "callback never
                                // ran" look identical to "native side rejected the surface".
                                runCatching { CpWindow.nativeAttachSurface(holder.surface) }
                                    .onSuccess { Diag.log("cpwin", "surfaceCreated -> attach invoked") }
                                    .onFailure { Diag.log("cpwin", "attach failed: ${it.message}") }
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {
                                Diag.log("cpwin", "surfaceChanged ${w}x$height")
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                runCatching { CpWindow.nativeDetachSurface() }
                            }
                        },
                    )
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Show the TAIL, not the head: a Python traceback ends with the actual
                // exception, which is the only line that matters.
                text = if (output.isBlank()) status else "$status\n${output.takeLast(300)}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                runCatching { CpWindow.nativeRequestClose() }
                onClose()
            }) {
                Text("关闭", fontSize = 12.sp)
            }
        }
    }
}
