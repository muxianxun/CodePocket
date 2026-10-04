"""Runs Python source inside the app process (Chaquopy) and captures its output.

Chaquopy embeds CPython as a library, so this works on Android 10+ where executing a
bundled python binary out of the app's data directory is blocked by the platform.
"""

import contextlib
import io
import os
import runpy
import sys
import traceback


def environment_info():
    """Short summary for the UI: version, implementation and where it runs from."""
    try:
        prefix = sys.prefix
    except Exception:
        prefix = "?"
    return "%s (%s) · %s" % (sys.version.split()[0], sys.implementation.name, prefix)


def run_file(path, argv=None):
    """Executes *path* as __main__ and returns everything it printed.

    The working directory is switched to the script's folder so relative file access
    behaves the way the user expects.
    """
    buffer = io.StringIO()
    old_cwd = os.getcwd()
    old_argv = list(sys.argv)
    directory = os.path.dirname(os.path.abspath(path))
    try:
        if directory and os.path.isdir(directory):
            os.chdir(directory)
        sys.argv = [os.path.basename(path)] + [str(a) for a in (argv or [])]
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            runpy.run_path(path, run_name="__main__")
    except SystemExit as exc:
        if exc.code not in (None, 0):
            buffer.write("\n[程序以状态 %s 退出]\n" % (exc.code,))
    except BaseException:
        buffer.write("\n" + traceback.format_exc())
    finally:
        sys.argv = old_argv
        try:
            os.chdir(old_cwd)
        except Exception:
            pass
    return buffer.getvalue()


def run_source(code, filename="<snippet>"):
    """Executes a code string (used by the AI panel to try a snippet)."""
    buffer = io.StringIO()
    try:
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            exec(compile(code, filename, "exec"), {"__name__": "__main__"})
    except BaseException:
        buffer.write("\n" + traceback.format_exc())
    return buffer.getvalue()
