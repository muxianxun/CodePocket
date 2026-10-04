package com.dsh.codepocket.terminal

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One interactive shell running inside a pseudoterminal.
 *
 * The shell inherits this app's uid. On a rooted device the user can still get a
 * root shell by typing `su` inside the terminal.
 */
class TerminalSession(
    private val workDir: String,
    private val homeDir: String,
) {

    companion object {
        private const val TAG = "TerminalSession"
        const val SHELL = "/system/bin/sh"
        const val DEFAULT_PATH =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin" +
                ":/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var toPty: FileOutputStream? = null
    private var fromPty: FileInputStream? = null
    private val running = AtomicBoolean(false)
    private var reader: Thread? = null

    var pid: Int = -1
        private set

    var masterFd: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    /** Creates the pty + shell. [onOutput] is invoked from a background thread. */
    fun start(
        rows: Int,
        cols: Int,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
    ): Boolean {
        if (running.get()) return true
        if (!PtyNative.isAvailable) {
            Log.e(TAG, "native library unavailable")
            return false
        }

        val pidOut = IntArray(1)
        val fd = try {
            PtyNative.createSubprocess(SHELL, workDir, homeDir, DEFAULT_PATH, pidOut, rows, cols)
        } catch (t: Throwable) {
            Log.e(TAG, "createSubprocess threw", t)
            -1
        }
        if (fd < 0) {
            Log.e(TAG, "forkpty returned $fd")
            return false
        }

        masterFd = fd
        pid = pidOut[0]

        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        pfd = descriptor
        toPty = FileOutputStream(descriptor.fileDescriptor)
        fromPty = FileInputStream(descriptor.fileDescriptor)
        running.set(true)

        reader = thread(name = "pty-reader-$pid", isDaemon = true) {
            val buffer = ByteArray(8192)
            try {
                val input = fromPty
                while (running.get() && input != null) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) onOutput(buffer.copyOf(n))
                }
            } catch (t: Throwable) {
                Log.d(TAG, "reader finished: ${t.javaClass.simpleName} ${t.message}")
            }
            running.set(false)
            val code = try {
                PtyNative.waitFor(pid)
            } catch (t: Throwable) {
                -1
            }
            onExit(code)
        }
        return true
    }

    fun write(bytes: ByteArray) {
        try {
            toPty?.write(bytes)
            toPty?.flush()
        } catch (t: Throwable) {
            Log.d(TAG, "write failed: ${t.message}")
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(rows: Int, cols: Int) {
        if (masterFd >= 0) {
            try {
                PtyNative.setWinSize(masterFd, rows, cols)
            } catch (_: Throwable) {
            }
        }
    }

    /** Asks the shell to exit, then makes sure the child is gone and the fd is closed. */
    fun close() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            write("\nexit\n")
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
            }
            if (pid > 0) {
                try {
                    Process.sendSignal(pid, 9)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
        pfd = null
        toPty = null
        fromPty = null
        masterFd = -1
        reader = null
    }
}
