/*
 * PTY support for CodePocket's terminal.
 *
 * Design notes:
 *  - forkpty() is available in Android's bionic libc since API 23
 *    (declared in <pty.h>), and was verified working on the target device.
 *  - The child inherits the app's uid and gets the pty slave as its
 *    controlling terminal, so /system/bin/sh behaves like a real login shell
 *    (job control, isatty() == true, line editing, colours).
 *  - Only process creation, window sizing and reaping live here. Actual
 *    read/write on the master fd is done from Kotlin via
 *    ParcelFileDescriptor.adoptFd(), which keeps the JNI surface tiny.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "CodePocketPTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (s == NULL) {
        return NULL;
    }
    const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
    if (chars == NULL) {
        return NULL;
    }
    char *copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, s, chars);
    return copy;
}

JNIEXPORT jint JNICALL
Java_com_dsh_codepocket_terminal_PtyNative_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring jCmd, jstring jCwd, jstring jHome, jstring jPath,
        jintArray jPidOut, jint rows, jint cols) {

    char *cmd = dup_jstring(env, jCmd);
    char *cwd = dup_jstring(env, jCwd);
    char *home = dup_jstring(env, jHome);
    char *path = dup_jstring(env, jPath);

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    size.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &size);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free(cmd);
        free(cwd);
        free(home);
        free(path);
        return -1;
    }

    if (pid == 0) {
        /* ---- child ---- */
        if (cwd != NULL && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                /* keep going: a bad cwd must not kill the shell */
                chdir("/");
            }
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        setenv("LANG", "en_US.UTF-8", 1);
        /* Android's mkshrc builds a very long PS1 ("host:/full/path $ ") which
           wraps on every command in a phone-width terminal. Source nothing and
           use a short prompt instead. */
        setenv("ENV", "/dev/null", 1);
        setenv("PS1", "$ ", 1);
        setenv("PS2", "> ", 1);
        if (home != NULL && home[0] != '\0') {
            setenv("HOME", home, 1);
        }
        if (path != NULL && path[0] != '\0') {
            setenv("PATH", path, 1);
        }
        setenv("SHELL", (cmd != NULL && cmd[0] != '\0') ? cmd : "/system/bin/sh", 1);

        if (cmd != NULL && cmd[0] != '\0') {
            execl(cmd, cmd, (char *) NULL);
        }
        execl("/system/bin/sh", "sh", (char *) NULL);
        _exit(127);
    }

    /* ---- parent ---- */
    if (jPidOut != NULL) {
        jint pidValue = (jint) pid;
        (*env)->SetIntArrayRegion(env, jPidOut, 0, 1, &pidValue);
    }

    free(cmd);
    free(cwd);
    free(home);
    free(path);
    return master;
}

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_terminal_PtyNative_setWinSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    size.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    if (ioctl(fd, TIOCSWINSZ, &size) != 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_dsh_codepocket_terminal_PtyNative_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_terminal_PtyNative_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) {
        close(fd);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_dsh_codepocket_terminal_PtyNative_setNonBlocking(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        return JNI_FALSE;
    }
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0 ? JNI_TRUE : JNI_FALSE;
}
