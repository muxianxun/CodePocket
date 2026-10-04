package com.dsh.codepocket.terminal

/**
 * Thin JNI surface for pseudoterminal handling (see src/main/cpp/pty.c).
 *
 * All byte-level I/O happens in Kotlin through ParcelFileDescriptor.adoptFd();
 * only process creation, window sizing and reaping are implemented natively.
 */
object PtyNative {

    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("codepocket")
            true
        } catch (t: Throwable) {
            android.util.Log.e("PtyNative", "failed to load libcodepocket.so", t)
            false
        }
    }

    val isAvailable: Boolean get() = loaded

    /**
     * Forks a child process attached to a new pty and runs [cmd] (default shell when blank).
     *
     * @return the pty master file descriptor, or -1 on failure.
     */
    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        home: String,
        path: String,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    @JvmStatic
    external fun setWinSize(fd: Int, rows: Int, cols: Int)

    /** Blocks until [pid] exits; returns its exit status. */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun closeFd(fd: Int)

    @JvmStatic
    external fun setNonBlocking(fd: Int): Boolean
}
