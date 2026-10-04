package com.dsh.codepocket

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Owns process-wide initialisation.
 *
 * Chaquopy refuses to run until a platform is registered: without
 * `Python.start(AndroidPlatform(context))` the first call fails with
 * "Cannot use GenericPlatform on Android". Doing it here means every entry point
 * (editor, AI panel, background service) can simply use `CodePython`.
 */
class CodePocketApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Diag.clear()
        Diag.log("app", "onCreate v0.2.0 sdk=${android.os.Build.VERSION.SDK_INT}")

        val started = System.currentTimeMillis()
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            Diag.log("python", "Python.start ok in ${System.currentTimeMillis() - started}ms")
        } catch (t: Throwable) {
            // Never fatal: the editor and terminal still work without Python.
            Diag.log("python", "Python.start failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
