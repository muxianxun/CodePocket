package com.dsh.codepocket.editor

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import com.dsh.codepocket.files.FileRepository
import com.dsh.codepocket.python.CodePython
import com.dsh.codepocket.web.AssetWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.io.File

class EditorBridge(
    private val changedCb: (String) -> Unit,
    private val commitCb: (String) -> Unit,
    private val readyCb: () -> Unit,
) {
    @JavascriptInterface
    fun onChanged(base64: String) = changedCb(base64)

    @JavascriptInterface
    fun commit(base64: String) = commitCb(base64)

    @JavascriptInterface
    fun onReady() = readyCb()

    @JavascriptInterface
    fun log(message: String) {
        Diag.log("editor-web", message)
    }
}

@Composable
fun EditorScreen(
    repo: FileRepository,
    file: File?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (file == null) "未打开文件" else file.name) }
    var fontSize by remember { mutableStateOf(13) }
    var runOutput by remember { mutableStateOf<String?>(null) }
    var runStatus by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var pythonInfo by remember { mutableStateOf("") }
    /** Non-null while a compiled program owns the screen in its own graphics window. */
    var windowLib by remember { mutableStateOf<String?>(null) }
    /** Non-null while the built-in browser is showing a preview or a local backend. */
    var browserUrl by remember { mutableStateOf<String?>(null) }

    // Report the embedded CPython version once, so "内置 Python" is verifiable.
    LaunchedEffect(Unit) {
        pythonInfo = CodePython.environment()
        Diag.log("editor", "python env: $pythonInfo")
    }

    fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun save(base64Content: String) {
        val target = file ?: return
        scope.launch {
            val bytes = Base64.decode(base64Content, Base64.DEFAULT)
            val text = String(bytes, Charsets.UTF_8)
            val ok = withContext(Dispatchers.IO) {
                runCatching { repo.write(target, text) }.isSuccess
            }
            dirty = !ok
            status = if (ok) "已保存 · ${text.length} 字符" else "保存失败"
            Diag.log("editor", "save ${target.name} ok=$ok chars=${text.length}")
        }
    }

    /**
     * Saves the buffer first so the file on disk is what actually runs, then dispatches to
     * the right language backend and shows the combined output.
     */
    fun runSource(target: File, context: android.content.Context) {
        val ext = target.name.substringAfterLast('.', "").lowercase()
        val view = webView
        if (view == null) {
            runStatus = "编辑器未就绪"
            return
        }
        running = true
        runStatus = "正在保存并${if (ext == "py") "运行" else "编译"} ${target.name} …"
        view.evaluateJavascript("window.Editor.getContentB64()") { result ->
            val decoded = decodeJsString(result)
            if (decoded == null) {
                running = false
                runStatus = "读取编辑器内容失败"
                return@evaluateJavascript
            }
            scope.launch {
                val text = String(Base64.decode(decoded, Base64.DEFAULT), Charsets.UTF_8)
                withContext(Dispatchers.IO) { runCatching { repo.write(target, text) } }
                dirty = false
                val started = System.currentTimeMillis()
                val output = RunDispatcher.run(context, target, ext)
                val elapsed = System.currentTimeMillis() - started
                runOutput = output
                runStatus = "${target.name} · ${elapsed}ms"
                running = false
            }
        }
    }

    val bridge = remember {
        EditorBridge(
            changedCb = { dirty = true },
            commitCb = { base64 -> save(base64) },
            readyCb = { },
        )
    }

    // Load the file into CodeMirror whenever the selection changes.
    LaunchedEffect(file?.absolutePath) {
        val target = file ?: return@LaunchedEffect
        status = target.name
        val text = withContext(Dispatchers.IO) {
            runCatching { repo.read(target) }.getOrElse { "" }
        }
        val mode = FileRepository.languageFor(target.name)
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // The WebView may not be ready yet on first composition; retry briefly.
        repeat(10) {
            if (webView != null) return@repeat
            kotlinx.coroutines.delay(120)
        }
        kotlinx.coroutines.delay(150)
        js("window.Editor && window.Editor.setContentB64('$b64', '$mode')")
        dirty = false
        Diag.log("editor", "loaded ${target.name} mode=$mode chars=${text.length}")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = if (dirty) "$status ●" else status,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (dirty) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolButton("撤销") { js("window.Editor && window.Editor.undo()") }
            ToolButton("重做") { js("window.Editor && window.Editor.redo()") }
            ToolButton("A-") {
                fontSize = (fontSize - 2).coerceAtLeast(8)
                js("window.Editor && window.Editor.setFontSize($fontSize)")
            }
            ToolButton("A+") {
                fontSize = (fontSize + 2).coerceAtMost(28)
                js("window.Editor && window.Editor.setFontSize($fontSize)")
            }
            ToolButton("保存") {
                val view = webView
                if (view == null) {
                    status = "编辑器未就绪"
                } else {
                    view.evaluateJavascript("window.Editor.getContentB64()") { result ->
                        val decoded = decodeJsString(result)
                        if (decoded != null) {
                            save(decoded)
                        } else {
                            status = "读取内容失败"
                        }
                    }
                }
            }
            // Every supported type gets a run button: Python interprets directly, Java and
            // C/C++ compile first. The dispatcher decides which backend handles it.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val ext = file?.name?.substringAfterLast('.', "")?.lowercase() ?: ""
            ToolButton(
                label = if (running) "运行中…" else RunDispatcher.buttonLabel(ext),
                enabled = RunDispatcher.supports(ext) && !running,
            ) {
                file?.let { runSource(it, ctx) }
            }
            // Java gets a real Android interface instead of a canvas: the compiled dex is
            // loaded into this process, so the user's class receives a Context and can build
            // genuine views (see runtime/JavaUiHost.kt).
            //
            // Rendered ONLY for .java. Material3's TextButton enforces a 58dp minimum width
            // (ButtonDefaults.MinWidth) whatever the label says, so every extra button costs
            // ~172px on this screen; nine of them needed ~1550px on a 1220px screen and the
            // right-most buttons were clipped off entirely, making the window/UI features
            // unreachable. Shrinking padding or labels could not help — the floor belongs to
            // the component. Measured on device.
            if (ext == "java") {
                ToolButton(
                    label = "界面",
                    enabled = !running && windowLib == null && browserUrl == null,
                ) {
                    val target = file
                    if (target != null) {
                        scope.launch {
                            running = true
                            runStatus = "正在把 ${target.name} 编译成 dex 并载入…"
                            val result = com.dsh.codepocket.runtime.JavaUiHost.prepare(ctx, target)
                            running = false
                            result.fold(
                                onSuccess = { prepared ->
                                    // Reuse the window slot: "javaui:<dex>|<class>"
                                    windowLib = "javaui:" + prepared.dex.absolutePath + "|" + prepared.className
                                    runStatus = "界面已启动 · ${prepared.className}"
                                },
                                onFailure = { error ->
                                    runOutput = "界面运行失败：${error.message}"
                                    runStatus = target.name
                                },
                            )
                        }
                    }
                }
            }
            // Preview local HTML in the built-in browser. Rendered only for HTML/SVG for the
            // same width reason as the Java button above.
            val canPreview = ext == "html" || ext == "htm" || ext == "svg"
            if (canPreview) {
                ToolButton(
                    label = "预览",
                    enabled = windowLib == null && browserUrl == null,
                ) {
                    val target = file
                    if (target != null) {
                        // Preview what is on disk. Use 保存 first if you just edited the file.
                        browserUrl = "file://" + target.absolutePath
                        runStatus = "预览 ${target.name}（已保存内容）"
                    }
                }
            }
            // Removed the "本地服务" button: a toolbar with up to eight buttons overflowed the
            // screen and pushed the window/UI buttons off it (verified on device), and the
            // built-in browser has its own address bar, so the shortcut added nothing.
            val canWindow = ext == "c" || ext == "cpp" || ext == "cc" || ext == "cxx" ||
                ext == "py" || ext == "rs"
            if (canWindow) {
                ToolButton(
                label = "窗口",
                enabled = canWindow && !running && windowLib == null,
            ) {
                val target = file
                if (target != null) {
                    if (ext == "py") {
                        scope.launch {
                            running = true
                            runStatus = "准备窗口运行时（Python 无需编译）…"
                            com.dsh.codepocket.window.CpWindow.stageEngine(ctx)
                            running = false
                            windowLib = "py:" + target.absolutePath
                            runStatus = "窗口已启动 · ${target.name}"
                        }
                    } else {
                        scope.launch {
                            running = true
                            runStatus = "正在把 ${target.name} 编译成共享库（编译的是已保存内容）…"
                            val result = if (ext == "rs") {
                                com.dsh.codepocket.runtime.RustToolchain
                                    .compileWindowProgram(ctx, target)
                            } else {
                                com.dsh.codepocket.runtime.ClangToolchain
                                    .compileWindowProgram(ctx, target)
                            }
                            running = false
                            result.fold(
                                onSuccess = { lib ->
                                    windowLib = "lib:" + lib.absolutePath
                                    runStatus = "窗口已启动 · ${lib.name}"
                                },
                                onFailure = { error ->
                                    runOutput = "窗口编译失败：${error.message}"
                                    runStatus = target.name
                                },
                            )
                        }
                    }
                }
            }
            }
        }

        // Output of the last run, shown under the editor.
        runOutput?.let { output ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = runStatus,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ToolButton("清空") { runOutput = null }
                }
                Text(
                    text = output.ifBlank { "（没有输出）" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            val job = windowLib
            val site = browserUrl
            if (site != null) {
                // Built-in browser: local HTML preview or a backend on loopback.
                com.dsh.codepocket.web.BrowserScreen(
                    startUrl = site,
                    onClose = { browserUrl = null },
                )
            } else if (job != null && job.startsWith("javaui:")) {
                // Java: a real Android view hierarchy built inside this process.
                val payload = job.removePrefix("javaui:")
                val dexPath = payload.substringBefore('|')
                val className = payload.substringAfter('|', "")
                com.dsh.codepocket.runtime.JavaUiScreen(
                    dexPath = dexPath,
                    className = className,
                    onClose = {
                        windowLib = null
                        runStatus = "界面已关闭"
                    },
                )
            } else if (job != null) {
                // "py:<path>" runs a script, "lib:<path>" loads a compiled library; both
                // draw into the SurfaceView hosted here.
                val isScript = job.startsWith("py:")
                val path = job.substringAfter(':')
                com.dsh.codepocket.window.CpWindowScreen(
                    title = path.substringAfterLast('/'),
                    runProgram = {
                        if (isScript) {
                            val text = com.dsh.codepocket.python.CodePython.runFile(path)
                            if (text.isBlank()) "脚本已结束" else text.trim()
                        } else {
                            val rc = com.dsh.codepocket.window.CpWindow.nativeRunSharedLibrary(path)
                            "程序结束（返回 $rc）"
                        }
                    },
                    onClose = {
                        windowLib = null
                        runStatus = "窗口已关闭"
                    },
                )
            } else if (file == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有打开文件\n去「文件」页选一个，或新建一个",
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AssetWebView(
                    assetPath = "web/editor.html",
                    jsInterface = bridge,
                    modifier = Modifier.fillMaxSize(),
                    onPageReady = { view -> webView = view },
                )
            }
        }
    }
}

/** evaluateJavascript hands back a JSON literal, so a JS string arrives quoted and escaped. */
private fun decodeJsString(raw: String?): String? {
    if (raw == null || raw == "null") return null
    return try {
        JSONTokener(raw).nextValue() as? String
    } catch (t: Throwable) {
        Diag.log("editor", "decode failed: ${t.message}")
        null
    }
}

@Composable
private fun ToolButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        // Tight padding + 11sp on purpose: a .rs/.c/.py/.java file now shows up to seven of
        // these (撤销 重做 A- A+ 保存 + two run buttons) and the row overflowed the screen —
        // the right-most button was clipped down to a single half-visible character, which
        // made the window/UI/browser features unreachable. Verified on device.
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(text = label, fontSize = 11.sp, maxLines = 1)
    }
}
