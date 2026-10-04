package com.dsh.codepocket.terminal

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.codepocket.Diag
import com.dsh.codepocket.web.AssetWebView
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/** Quick keys: a phone soft keyboard cannot express these comfortably. */
private data class QuickKey(val label: String, val data: String)

private val QUICK_KEYS = listOf(
    QuickKey("ESC", "\u001b"),
    QuickKey("TAB", "\t"),
    QuickKey("^C", "\u0003"),
    QuickKey("^D", "\u0004"),
    QuickKey("^Z", "\u001a"),
    QuickKey("↑", "\u001b[A"),
    QuickKey("↓", "\u001b[B"),
    QuickKey("←", "\u001b[D"),
    QuickKey("→", "\u001b[C"),
    QuickKey("/", "/"),
    QuickKey("-", "-"),
    QuickKey("|", "|"),
    QuickKey("~", "~"),
    QuickKey("⏎", "\r"),
)

/**
 * Exposed to terminal.html as `window.Android`.
 * The WebView invokes these on a private thread, so callbacks marshal back to the
 * main thread before touching Compose state or the WebView.
 */
class TerminalBridge(
    private val writeCb: (String) -> Unit,
    private val resizeCb: (Int, Int) -> Unit,
    private val readyCb: (Int, Int) -> Unit,
    private val tapCb: () -> Unit,
) {
    @JavascriptInterface
    fun writeText(text: String) = writeCb(text)

    @JavascriptInterface
    fun resize(rows: Int, cols: Int) = resizeCb(rows, cols)

    @JavascriptInterface
    fun onReady(rows: Int, cols: Int) = readyCb(rows, cols)

    @JavascriptInterface
    fun onTap() = tapCb()

    @JavascriptInterface
    fun log(message: String) {
        Diag.log("term-web", message)
    }
}

@Composable
fun TerminalScreen(workDir: String, modifier: Modifier = Modifier) {
    val session = remember(workDir) { TerminalSession(workDir, workDir) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("正在加载终端…") }
    var fontSize by remember { mutableStateOf(13) }
    var readyToken by remember { mutableStateOf(0) }
    var termRows by remember { mutableStateOf(24) }
    var termCols by remember { mutableStateOf(80) }
    var inputText by remember { mutableStateOf("") }

    val pending = remember { ByteArrayOutputStream() }
    val pendingLock = remember { Any() }

    fun js(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    // Primary text entry on a phone. xterm's hidden textarea is unreliable with
    // Chinese IMEs in an Android WebView (verified: pinyin compositions never
    // reach the DOM), so commands are typed into a real Compose text field and
    // written to the pty here.
    fun sendLine() {
        val text = inputText
        session.write(text + "\r")
        Diag.log("input", "sendLine len=${text.length}")
        inputText = ""
    }

    val bridge = remember {
        TerminalBridge(
            writeCb = { text -> session.write(text) },
            resizeCb = { rows, cols -> session.resize(rows, cols) },
            readyCb = { rows, cols ->
                mainHandler.post {
                    if (rows > 0 && cols > 0) {
                        termRows = rows
                        termCols = cols
                    }
                    readyToken += 1
                }
            },
            tapCb = {
                mainHandler.post { webView?.requestFocus() }
            },
        )
    }

    // Start the shell once the page reports real terminal dimensions.
    LaunchedEffect(readyToken, workDir) {
        if (readyToken == 0) return@LaunchedEffect
        if (session.isRunning) session.close()
        synchronized(pendingLock) { pending.reset() }

        status = "正在启动 shell…"
        val ok = session.start(
            rows = termRows,
            cols = termCols,
            onOutput = { bytes -> synchronized(pendingLock) { pending.write(bytes) } },
            onExit = { code -> mainHandler.post { status = "shell 已退出（状态码 $code）" } },
        )
        Diag.log("terminal", "start ok=$ok pid=${session.pid} fd=${session.masterFd} size=${termCols}x${termRows}")

        if (ok) {
            status = "运行中 · pid ${session.pid}"
            // The shell printed its first prompt before the terminal was fitted;
            // drop that and ask for a fresh prompt at the real width.
            delay(300)
            js("window.Terminal.reset()")
            delay(120)
            session.write("\r")
        } else {
            status = "启动失败：native 库不可用"
        }
    }

    // Wire the package manager into this shell: install the `pkg` shim, start the request
    // watcher, and export the toolchain prefix so `pkg` and anything it installs are usable.
    // Fully-qualified on purpose — keeps this to a single edit with no new imports.
    val pkgContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(workDir) {
        runCatching {
            val shim = com.dsh.codepocket.runtime.PkgBridge.ensureShim(pkgContext)
            com.dsh.codepocket.runtime.PkgBridge.startWatcher(pkgContext)
            session.write(com.dsh.codepocket.runtime.PkgBridge.shellExports(pkgContext))
            com.dsh.codepocket.Diag.log(
                "pkg",
                "terminal wired: shim=${shim.absolutePath} exists=${shim.exists()}",
            )
        }.onFailure {
            com.dsh.codepocket.Diag.log("pkg", "terminal wiring failed: ${it.message}")
        }
    }

    // Drain pty output into the WebView, batched so it cannot be flooded.
    LaunchedEffect(Unit) {
        while (true) {
            delay(24)
            val view = webView ?: continue
            val chunk = synchronized(pendingLock) {
                if (pending.size() == 0) {
                    null
                } else {
                    val bytes = pending.toByteArray()
                    pending.reset()
                    bytes
                }
            }
            if (chunk != null && chunk.isNotEmpty()) {
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                view.evaluateJavascript("window.Terminal.writeB64('$b64')", null)
            }
        }
    }

    DisposableEffect(workDir) {
        onDispose { session.close() }
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
        ) {
            Text(
                text = status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ToolbarButton("清屏") { js("window.Terminal.clearAll()") }
            // Clipboard. Long-press selection in xterm is fiddly, so "复制" copies the whole
            // buffer and "选中" is there for the picky case.
            ToolbarButton("复制") {
                webView?.evaluateJavascript("window.Terminal.getAllText()") { raw ->
                    // evaluateJavascript hands back a JSON literal: strip the quoting inline
                    // rather than dragging in a JSON helper for one call site.
                    val text = raw.orEmpty()
                        .trim()
                        .removeSurrounding("\"")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\r", "")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    if (text.isNotEmpty()) {
                        runCatching {
                            val clipboard = pkgContext
                                .getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("terminal", text),
                            )
                            status = "已复制 ${text.length} 字符"
                        }.onFailure { status = "复制失败：${it.message}" }
                    } else {
                        status = "终端里没有内容"
                    }
                }
            }
            ToolbarButton("选中") {
                webView?.evaluateJavascript("window.Terminal.selectAll()", null)
                status = "已全选；点「复制」复制整屏"
            }
            ToolbarButton("粘贴") {
                runCatching {
                    val clipboard = pkgContext
                        .getSystemService(android.content.ClipboardManager::class.java)
                    val text = clipboard?.primaryClip?.getItemAt(0)
                        ?.coerceToText(pkgContext)?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        // Land it in the input box first so it can be reviewed before sending.
                        inputText = inputText + text
                        status = "已粘贴 ${text.length} 字符到输入框"
                    } else {
                        status = "剪贴板是空的"
                    }
                }.onFailure { status = "粘贴失败：${it.message}" }
            }
            ToolbarButton("A-") {
                fontSize = (fontSize - 2).coerceAtLeast(8)
                js("window.Terminal.setFontSize($fontSize)")
            }
            ToolbarButton("A+") {
                fontSize = (fontSize + 2).coerceAtMost(28)
                js("window.Terminal.setFontSize($fontSize)")
            }
            ToolbarButton("键盘") {
                webView?.requestFocus()
                js("window.Terminal.focusTerm()")
            }
            ToolbarButton("重启") {
                readyToken += 1
                js("window.Terminal.reset()")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AssetWebView(
                assetPath = "web/terminal.html",
                jsInterface = bridge,
                modifier = Modifier.fillMaxSize(),
                onPageReady = { view ->
                    webView = view
                    view.requestFocus()
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                singleLine = true,
                placeholder = {
                    Text("输入命令，回车发送", fontSize = 12.sp, maxLines = 1)
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { sendLine() }),
                shape = RoundedCornerShape(8.dp),
            )
            TextButton(
                onClick = { sendLine() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text("发送 ⏎", fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QUICK_KEYS.forEach { key ->
                TextButton(
                    onClick = {
                        session.write(key.data)
                        Diag.log("quickkey", "sent ${key.label}")
                    },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = key.label,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}
