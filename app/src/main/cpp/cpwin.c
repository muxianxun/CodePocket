/*
 * cpwin.c —— 掌上代码的图形窗口实现（软件渲染）
 *
 * 设计要点
 * ---------------------------------------------------------------------------
 * - 不依赖 GL/EGL：cp_present() 用 ANativeWindow_lock 直接写像素，任何设备都能出画面。
 * - 帧缓冲是我们自己的 uint32 数组，绘图函数先画在内存里，present 时整块拷过去，
 *   并按 stride 处理行对齐（Surface 的每行可能有 padding，不能假设紧密排列）。
 * - 逻辑分辨率与 Surface 尺寸解耦：等比缩放居中，留黑边而不是拉伸变形。
 * - Surface 由 Kotlin 侧的 SurfaceView 通过 JNI 交进来；程序本体由 App 用
 *   System.load + dlsym("main") 在后台线程启动。
 *
 * 线程约定：全部 cp_* 绘图接口必须在同一个线程调用（用户程序的 main 线程）。
 *   Surface 的附属/解附发生在 UI 线程，用互斥保护共享状态。
 */

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <dlfcn.h>
#include <unistd.h>

#include "codepocket.h"

#define LOG_TAG "cpwin"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ 状态 */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static ANativeWindow *g_window = NULL;   /* UI 线程附属 */
static int32_t g_surface_w = 0;
static int32_t g_surface_h = 0;

static uint32_t *g_framebuffer = NULL;   /* 逻辑分辨率画布 */
static int g_width = 0;
static int g_height = 0;
static int g_opened = 0;                 /* cp_open 成功 */
static volatile int g_closing = 0;       /* 应尽快结束 */
static struct timespec g_start_time;
static uint32_t g_clear_color = 0xff000000u;

#define CP_KEY_QUEUE 32
static int g_keys[CP_KEY_QUEUE];
static int g_key_head = 0;
static int g_key_tail = 0;

static void push_key(int code) {
    int next = (g_key_tail + 1) % CP_KEY_QUEUE;
    if (next == g_key_head) return;      /* 满了就丢，不阻塞绘图 */
    g_keys[g_key_tail] = code;
    g_key_tail = next;
}

/* ------------------------------------------------------------------ 基础 */

static uint32_t rgb_to_argb(unsigned int rgb) {
    return 0xff000000u | (uint32_t)(rgb & 0x00ffffffu);
}

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
}

unsigned int cp_ticks_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t start = (uint64_t)g_start_time.tv_sec * 1000ull +
                     (uint64_t)g_start_time.tv_nsec / 1000000ull;
    uint64_t now = (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
    return (unsigned int)(now - start);
}

void cp_sleep_ms(int ms) {
    if (ms <= 0) return;
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

int cp_width(void) { return g_width; }
int cp_height(void) { return g_height; }
int cp_should_close(void) { return g_closing; }

int cp_poll_key(void) {
    int code = 0;
    pthread_mutex_lock(&g_lock);
    if (g_key_head != g_key_tail) {
        code = g_keys[g_key_head];
        g_key_head = (g_key_head + 1) % CP_KEY_QUEUE;
    }
    pthread_mutex_unlock(&g_lock);
    return code;
}

/* ------------------------------------------------------------------ 开窗 */

int cp_open(int width, int height, const char *title) {
    (void)title;                          /* 标题栏由 Android 窗口系统决定 */
    if (width <= 0 || height <= 0) return -1;

    pthread_mutex_lock(&g_lock);
    free(g_framebuffer);
    g_framebuffer = (uint32_t *)calloc((size_t)width * (size_t)height, sizeof(uint32_t));
    if (g_framebuffer == NULL) {
        pthread_mutex_unlock(&g_lock);
        LOGI("cp_open: framebuffer allocation failed (%dx%d)", width, height);
        return -1;
    }
    g_width = width;
    g_height = height;
    g_opened = 1;
    g_closing = 0;
    g_key_head = g_key_tail = 0;
    g_clear_color = 0xff000000u;
    clock_gettime(CLOCK_MONOTONIC, &g_start_time);
    ANativeWindow *window = g_window;
    pthread_mutex_unlock(&g_lock);

    /* 等 Surface 就绪（SurfaceView 可能比程序启动稍晚）。 */
    for (int waited = 0; waited < 5000 && window == NULL && !g_closing; waited += 50) {
        cp_sleep_ms(50);
        pthread_mutex_lock(&g_lock);
        window = g_window;
        pthread_mutex_unlock(&g_lock);
    }
    if (window == NULL) {
        LOGI("cp_open: surface not ready after 5s");
        return -1;
    }

    /* 让缓冲尺寸与逻辑分辨率一致；present 时再按 stride 缩放铺满。 */
    ANativeWindow_setBuffersGeometry(window, g_width, g_height, WINDOW_FORMAT_RGBA_8888);
    LOGI("cp_open ok: %dx%d", g_width, g_height);
    return 0;
}

void cp_close(void) {
    pthread_mutex_lock(&g_lock);
    g_opened = 0;
    g_closing = 1;
    free(g_framebuffer);
    g_framebuffer = NULL;
    pthread_mutex_unlock(&g_lock);
}

/* ------------------------------------------------------------------ 绘图 */

static inline void put_pixel_locked(int x, int y, uint32_t argb) {
    if (x < 0 || y < 0 || x >= g_width || y >= g_height) return;
    g_framebuffer[(size_t)y * (size_t)g_width + (size_t)x] = argb;
}

void cp_clear(unsigned int rgb) {
    uint32_t argb = rgb_to_argb(rgb);
    g_clear_color = argb;
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) {
        size_t total = (size_t)g_width * (size_t)g_height;
        for (size_t i = 0; i < total; i++) g_framebuffer[i] = argb;
    }
    pthread_mutex_unlock(&g_lock);
}

void cp_pixel(int x, int y, unsigned int rgb) {
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) put_pixel_locked(x, y, rgb_to_argb(rgb));
    pthread_mutex_unlock(&g_lock);
}

void cp_rect(int x, int y, int w, int h, unsigned int rgb) {
    uint32_t argb = rgb_to_argb(rgb);
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) {
        for (int j = y; j < y + h; j++)
            for (int i = x; i < x + w; i++) put_pixel_locked(i, j, argb);
    }
    pthread_mutex_unlock(&g_lock);
}

void cp_rect_outline(int x, int y, int w, int h, unsigned int rgb) {
    uint32_t argb = rgb_to_argb(rgb);
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) {
        for (int i = x; i < x + w; i++) {
            put_pixel_locked(i, y, argb);
            put_pixel_locked(i, y + h - 1, argb);
        }
        for (int j = y; j < y + h; j++) {
            put_pixel_locked(x, j, argb);
            put_pixel_locked(x + w - 1, j, argb);
        }
    }
    pthread_mutex_unlock(&g_lock);
}

void cp_line(int x0, int y0, int x1, int y1, unsigned int rgb) {
    uint32_t argb = rgb_to_argb(rgb);
    int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
    int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) {
        while (1) {
            put_pixel_locked(x0, y0, argb);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }
    pthread_mutex_unlock(&g_lock);
}

void cp_circle(int cx, int cy, int radius, unsigned int rgb) {
    uint32_t argb = rgb_to_argb(rgb);
    if (radius <= 0) return;
    int r2 = radius * radius;
    pthread_mutex_lock(&g_lock);
    if (g_framebuffer != NULL) {
        for (int j = -radius; j <= radius; j++) {
            for (int i = -radius; i <= radius; i++) {
                if (i * i + j * j <= r2) put_pixel_locked(cx + i, cy + j, argb);
            }
        }
    }
    pthread_mutex_unlock(&g_lock);
}

/* ------------------------------------------------------------------ 呈现 */

void cp_present(void) {
    ANativeWindow *window;
    pthread_mutex_lock(&g_lock);
    window = g_window;
    pthread_mutex_unlock(&g_lock);
    if (window == NULL) return;

    ANativeWindow_Buffer buffer;
    /* 超时 0：拿不到缓冲就跳过这一帧，绝不阻塞用户程序的帧循环。 */
    if (ANativeWindow_lock(window, &buffer, NULL) != 0) return;

    pthread_mutex_lock(&g_lock);
    if (g_framebuffer == NULL) {
        pthread_mutex_unlock(&g_lock);
        ANativeWindow_unlockAndPost(window);
        return;
    }

    uint32_t *dst = (uint32_t *)buffer.bits;
    int32_t bw = buffer.width;
    int32_t bh = buffer.height;

    /* 等比缩放居中（留黑边），并处理 stride 行对齐。 */
    int scale_w = (g_width > 0) ? bw / g_width : 1;
    int scale_h = (g_height > 0) ? bh / g_height : 1;
    int scale = scale_w < scale_h ? scale_w : scale_h;
    if (scale < 1) scale = 1;
    int draw_w = g_width * scale;
    int draw_h = g_height * scale;
    int off_x = (bw - draw_w) / 2;
    int off_y = (bh - draw_h) / 2;

    /* 先铺黑边，再画缩放后的画面。 */
    for (int y = 0; y < bh; y++) {
        uint32_t *row = dst + (size_t)y * (size_t)buffer.stride;
        for (int x = 0; x < bw; x++) row[x] = 0xff000000u;
    }
    for (int y = 0; y < draw_h; y++) {
        int sy = off_y + y;
        if (sy < 0 || sy >= bh) continue;
        int src_y = y / scale;
        if (src_y >= g_height) continue;
        uint32_t *src_row = g_framebuffer + (size_t)src_y * (size_t)g_width;
        uint32_t *dst_row = dst + (size_t)sy * (size_t)buffer.stride;
        for (int x = 0; x < draw_w; x++) {
            int sx = off_x + x;
            if (sx < 0 || sx >= bw) continue;
            int src_x = x / scale;
            if (src_x >= g_width) continue;
            dst_row[sx] = src_row[src_x];
        }
    }
    pthread_mutex_unlock(&g_lock);

    ANativeWindow_unlockAndPost(window);
}

/* ------------------------------------------------------------------ JNI */

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_window_CpWindow_nativeAttachSurface(JNIEnv *env, jclass clazz, jobject surface) {
    (void)clazz;
    // Log every entry and every failure branch: the first version only logged success, which
    // made "never called" indistinguishable from "fromSurface returned NULL" during the very
    // failure this comment was written for.
    LOGI("nativeAttachSurface called: surface=%p", (void *)surface);
    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    if (surface != NULL) {
        g_window = ANativeWindow_fromSurface(env, surface);
        if (g_window != NULL) {
            g_surface_w = ANativeWindow_getWidth(g_window);
            g_surface_h = ANativeWindow_getHeight(g_window);
            LOGI("surface attached: %dx%d", g_surface_w, g_surface_h);
        } else {
            LOGI("ANativeWindow_fromSurface returned NULL");
        }
    } else {
        LOGI("attach called with a null surface");
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_window_CpWindow_nativeDetachSurface(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    g_closing = 1;
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_window_CpWindow_nativePushKey(JNIEnv *env, jclass clazz, jint code) {
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&g_lock);
    push_key((int)code);
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_dsh_codepocket_window_CpWindow_nativeRequestClose(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    g_closing = 1;
}

/*
 * 载入用户程序并执行它的 main()。
 *
 * 用 System.load(absPath) 把编译好的 .so 载入 App 进程（targetSdk 28 允许从数据
 * 目录 dlopen），再用 dlsym 找 main —— 支持 "main" 与 "cp_main" 两个名字。
 * 返回 0 表示已启动，负数表示失败（错误信息由 nativeRunMain 打到 logcat）。
 */
JNIEXPORT jint JNICALL
Java_com_dsh_codepocket_window_CpWindow_nativeRunSharedLibrary(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (c_path == NULL) return -2;
    void *handle = dlopen(c_path, RTLD_NOW | RTLD_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    if (handle == NULL) {
        LOGI("dlopen failed: %s", dlerror());
        return -1;
    }
    int (*entry)(void) = (int (*)(void))dlsym(handle, "main");
    if (entry == NULL) entry = (int (*)(void))dlsym(handle, "cp_main");
    if (entry == NULL) {
        LOGI("no main/cp_main symbol in library");
        dlclose(handle);
        return -2;
    }
    LOGI("running user program");
    int rc = entry();
    LOGI("user program returned %d", rc);
    dlclose(handle);
    return rc;
}
