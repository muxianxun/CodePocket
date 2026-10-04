package com.dsh.codepocket

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics.
 *
 * logcat is unavailable on some ROMs (verified: `adb logcat -d` returns nothing on
 * the target device), so runtime evidence is appended to a plain file in the app's
 * external files dir, which can be read with `adb pull` or `run-as ... cat`.
 */
object Diag {

    private const val TAG = "CodePocket"

    @Volatile
    private var logFile: File? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "diag.log")
        } catch (t: Throwable) {
            Log.e(TAG, "diag init failed", t)
        }
    }

    val path: String? get() = logFile?.absolutePath

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        Log.i(TAG, "$tag: $message")
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            logFile?.writeText("")
        } catch (_: Throwable) {
        }
    }
}
