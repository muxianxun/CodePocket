"""掌上代码 · 图形窗口 API（Python 版）

和 C 版共用同一套原生实现（`cpwin.c`）：Python 通过 `ctypes` 调用
`libcodepocket.so` 里的 `cp_*` 函数。

为什么 Python 不需要编译：Chaquopy 让 CPython 跑在 **App 进程内**，而窗口只属于
App 的 Activity —— 所以 Python 天然就拿得到 Surface，直接画就行。

用法：

    import codepocket as cp

    with cp.open(480, 640, "我的窗口") as win:
        for i in range(200):
            if not win.alive():
                break
            win.clear(0x0b1020)
            win.line(240, 320, 40 + i * 2, 100 + i, 0x2ad4ff)
            win.circle(240, 320, 24 + i % 30, 0xffcc00)
            win.present()
            win.sleep(30)

坐标原点在左上角，颜色是 0xRRGGBB。绘图接口必须在同一个线程调用（本模块就是
被调用的那个线程）。**必须调用 present() 才会显示**。
"""

import builtins
import ctypes
import os
import time

__all__ = [
    "open", "Window", "KEY_ESC", "KEY_LEFT", "KEY_RIGHT", "KEY_UP", "KEY_DOWN",
    "KEY_ENTER", "KEY_SPACE",
]

KEY_ESC = 1
KEY_LEFT = 2
KEY_RIGHT = 3
KEY_UP = 4
KEY_DOWN = 5
KEY_ENTER = 6
KEY_SPACE = 7

_STATE_DIR = "/data/data/com.dsh.codepocket/files/cbuild/linklibs"
_PATH_FILE = os.path.join(_STATE_DIR, "engine_path.txt")

_lib = None


def _candidate_paths():
    """App 侧会把抽出来的 .so 绝对路径写进 engine_path.txt，优先用它。

    注意这里必须用 builtins.open：本模块把窗口构造函数命名成 `open`（对使用者更自然），
    于是模块内的裸 `open(...)` 会解析成它自己，导致递归 —— 实测过一次
    RecursionError，所以内部读写文件一律走 builtins.open。
    """
    paths = []
    # 先试裸 SONAME：App 已经用 System.loadLibrary("codepocket") 加载过这一份，
    # 链接器会复用它，于是 Python 和 JNI 看到的是**同一份全局变量**。
    # 若先按绝对路径打开，有可能加载出第二个实例（各自有独立的 g_window），
    # 那样 JNI 拿到 Surface 而 Python 永远等不到。
    paths.append("libcodepocket.so")
    try:
        with builtins.open(_PATH_FILE) as handle:
            recorded = handle.read().strip()
            if recorded:
                paths.append(recorded)
    except OSError:
        pass
    paths.append(os.path.join(_STATE_DIR, "libcodepocket.so"))
    return paths


def _api():
    global _lib
    if _lib is not None:
        return _lib

    last_error = None
    for path in _candidate_paths():
        try:
            lib = ctypes.CDLL(path)
        except OSError as exc:  # 继续试下一个
            last_error = exc
            continue

        lib.cp_open.restype = ctypes.c_int
        lib.cp_open.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p]
        lib.cp_close.restype = None
        lib.cp_clear.argtypes = [ctypes.c_uint]
        lib.cp_pixel.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        lib.cp_rect.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        lib.cp_rect_outline.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        lib.cp_line.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        lib.cp_circle.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]
        lib.cp_present.restype = None
        lib.cp_poll_key.restype = ctypes.c_int
        lib.cp_should_close.restype = ctypes.c_int
        lib.cp_sleep_ms.argtypes = [ctypes.c_int]
        lib.cp_ticks_ms.restype = ctypes.c_uint
        lib.cp_width.restype = ctypes.c_int
        lib.cp_height.restype = ctypes.c_int
        _lib = lib
        return lib

    raise OSError("无法加载 libcodepocket.so（最后错误：%s）" % last_error)


class Window:
    """一个逻辑分辨率的画布。逻辑分辨率与屏幕无关，present 时等比缩放居中。"""

    def __init__(self, width, height, title="CodePocket"):
        self._api = _api()
        code = self._api.cp_open(int(width), int(height), str(title).encode("utf-8"))
        if code != 0:
            raise RuntimeError(
                "cp_open 失败（返回 %d）——请确认是从编辑器的「窗口运行」启动的，"
                "否则没有 Surface 可用。" % code
            )
        self._t0 = time.time()
        self._closed = False

    # ---- 绘图 ----
    def clear(self, rgb=0x000000):
        """用颜色铺满整个画布。"""
        self._api.cp_clear(ctypes.c_uint(int(rgb)))

    def pixel(self, x, y, rgb):
        self._api.cp_pixel(int(x), int(y), ctypes.c_uint(int(rgb)))

    def rect(self, x, y, w, h, rgb):
        """实心矩形。"""
        self._api.cp_rect(int(x), int(y), int(w), int(h), ctypes.c_uint(int(rgb)))

    def rect_outline(self, x, y, w, h, rgb):
        self._api.cp_rect_outline(int(x), int(y), int(w), int(h), ctypes.c_uint(int(rgb)))

    def line(self, x0, y0, x1, y1, rgb):
        self._api.cp_line(int(x0), int(y0), int(x1), int(y1), ctypes.c_uint(int(rgb)))

    def circle(self, cx, cy, radius, rgb):
        """实心圆。"""
        self._api.cp_circle(int(cx), int(cy), int(radius), ctypes.c_uint(int(rgb)))

    def present(self):
        """把这一帧提交到屏幕。不调用则什么都看不到。"""
        self._api.cp_present()

    # ---- 事件与时间 ----
    def poll_key(self):
        return int(self._api.cp_poll_key())

    def alive(self):
        return self._api.cp_should_close() == 0

    def sleep(self, ms):
        self._api.cp_sleep_ms(int(ms))

    def ticks_ms(self):
        return int(self._api.cp_ticks_ms())

    @property
    def width(self):
        return int(self._api.cp_width())

    @property
    def height(self):
        return int(self._api.cp_height())

    def close(self):
        if not self._closed:
            self._closed = True
            self._api.cp_close()

    # ---- 上下文管理器：with cp.open(...) as win: ----
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False


def open(width, height, title="CodePocket"):
    """打开一个窗口。等价于 Window(width, height, title)。"""
    return Window(width, height, title)
