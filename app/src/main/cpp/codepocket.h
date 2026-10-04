/*
 * 掌上代码 CodePocket —— 图形窗口 API (v1)
 * ============================================================================
 *
 * 为什么需要这个头文件
 * ---------------------------------------------------------------------------
 * 在 Android 上，「弹出窗口」不是普通进程能做到的事：窗口必须由 Activity 的
 * Surface 提供，而一个从终端 PTY 里 fork 出来的独立进程拿不到 Surface。
 * 所以本 App 走的是 SDL 在 Android 上的同一条路：
 *
 *     你的程序  ──编译成共享库(.so)──►  App 进程内 dlopen
 *                                          │
 *                                          ├─ 你的 main() 在一个后台线程跑
 *                                          └─ cp_* 调用直接画进 App 的 SurfaceView
 *
 * 也就是说：**不再编译成可执行文件，而是编译成 .so**，由 App 载入执行。
 *
 * 用法
 * ---------------------------------------------------------------------------
 *      #include <codepocket.h>
 *
 *      int main(void) {
 *          cp_open(640, 480, "我的窗口");
 *          for (int i = 0; i < 100 && !cp_should_close(); i++) {
 *              cp_clear(0x101820);
 *              cp_line(0, 0, i * 6, 479, 0x00ff88);
 *              cp_circle(320, 240, 40 + i, 0xffcc00);
 *              cp_present();          // 提交这一帧（必须调用才会显示）
 *              cp_sleep_ms(33);       // 约 30fps
 *          }
 *          cp_close();
 *          return 0;
 *      }
 *
 * 编译参数（由 App 自动生成，这里列出是为了可读）：
 *      clang -shared -fPIC -I<include> -o libmain.so main.c \
 *            -L<nativeLibDir> -lcodepocket
 *
 * 约定与限制（v1）
 * ---------------------------------------------------------------------------
 *  - 坐标原点在左上角，颜色是 0xRRGGBB。
 *  - 软件渲染：cp_present() 把内存帧缓冲整块拷到 Surface，不依赖 GL/EGL，
 *    所以在任何设备上都能出画面（代价是没有硬件加速）。
 *  - 必须在**同一个线程**里调用全部 cp_* 绘图函数。
 *  - 分辨率由 cp_open 指定，会按比例缩放铺满屏幕（不是拉伸变形：留黑边）。
 *  - v1 还没有 cp_text()：点阵字体会单独加，避免这一版塞进无用代码。
 */

#ifndef CODEPOCKET_H
#define CODEPOCKET_H

#ifdef __cplusplus
extern "C" {
#endif

/* 按键码，cp_poll_key() 返回这些值；0 表示没有按键 */
#define CP_KEY_ESC   1
#define CP_KEY_LEFT  2
#define CP_KEY_RIGHT 3
#define CP_KEY_UP    4
#define CP_KEY_DOWN  5
#define CP_KEY_ENTER 6
#define CP_KEY_SPACE 7
#define CP_KEY_A     32 /* 'A'..'Z'、'0'..'9' 直接用 ASCII 值 */

/**
 * 打开窗口并设定逻辑分辨率。
 * 会等待 Surface 就绪（最多 ~5 秒）；若超时返回 -1，成功返回 0。
 * 逻辑分辨率与屏幕分辨率无关，present 时等比缩放。
 */
int cp_open(int width, int height, const char *title);

/** 关闭窗口并释放帧缓冲。程序退出前调用。 */
void cp_close(void);

/* ---- 绘图（颜色 0xRRGGBB，坐标越界会被安全裁剪） ---- */
void cp_clear(unsigned int rgb);
void cp_pixel(int x, int y, unsigned int rgb);
void cp_rect(int x, int y, int w, int h, unsigned int rgb);          /* 实心 */
void cp_rect_outline(int x, int y, int w, int h, unsigned int rgb);  /* 空心 */
void cp_line(int x0, int y0, int x1, int y1, unsigned int rgb);
void cp_circle(int cx, int cy, int radius, unsigned int rgb);        /* 实心圆 */

/** 把这一帧提交到屏幕。不调用则什么都看不到。 */
void cp_present(void);

/* ---- 事件与时间 ---- */
/** 取出一个按键（先进先出），没有则返回 0。 */
int cp_poll_key(void);
/** 非 0 表示窗口已被关闭（用户退出/App 切走），程序应尽快结束。 */
int cp_should_close(void);
void cp_sleep_ms(int ms);
/** 自 cp_open 起的毫秒数，可用于动画计时。 */
unsigned int cp_ticks_ms(void);

/** 逻辑分辨率（cp_open 成功后可读）。 */
int cp_width(void);
int cp_height(void);

#ifdef __cplusplus
}
#endif

#endif /* CODEPOCKET_H */

/*
 * 后续接线（尚未完成，按顺序）
 * ============================================================================
 * 1. cpp/cpwin.c 编进 libcodepocket.so（CMakeLists 里加源文件）——
 *    你的 .so 在运行时需要解析 cp_* 符号，而 libcodepocket.so 已被 App 为
 *    终端功能加载，符号天然可见，不需要额外的辅助库。
 * 2. Kotlin 侧一个全屏页：AndroidView { SurfaceView }，
 *    surfaceCreated → nativeAttachSurface(surface)，
 *    surfaceDestroyed → nativeDetachSurface()。
 * 3. 载入你的程序：System.load(absPath) 之后调用 nativeRunMain()，
 *    它用 dlsym 找 main 并在后台线程执行（targetSdk 28 允许从数据目录
 *    dlopen 与 exec，这正是 C/C++ 能跑起来的前提）。
 * 4. 编译入口：编辑器对 .c/.cpp 增加「运行（窗口）」，
 *    用 -shared -fPIC 生成 libmain.so 放进内部存储，再启动上面的页。
 * 5. 字体：加一张 5x7 点阵表实现 cp_text()。
 */
