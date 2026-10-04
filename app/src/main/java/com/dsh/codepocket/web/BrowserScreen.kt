package com.dsh.codepocket.web

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.codepocket.Diag

/**
 * Built-in browser: previews local HTML and talks to a locally started backend.
 *
 * Two jobs, one screen:
 *  - `file:///…/workspace/page.html` — preview what you just wrote
 *  - `http://127.0.0.1:8000` — hit a backend you started from the editor (Python's
 *    http.server, a Java server, anything listening on loopback)
 *
 * `allowFileAccess` is required for the file:// previews; cleartext to loopback has to be
 * permitted by the app's network security config, which the AI feature already needs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    startUrl: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf(startUrl) }
    var address by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("返回", fontSize = 12.sp) }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            TextButton(onClick = {
                val normalized = normalizeUrl(address)
                address = normalized
                target = normalized
                reloadToken++
            }) { Text("打开", fontSize = 12.sp) }
            TextButton(onClick = { webView?.reload() }) { Text("刷新", fontSize = 12.sp) }
        }

        Text(
            text = pageTitle.ifBlank { target },
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // Needed for file:// previews of the workspace.
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false

                            override fun onPageFinished(view: WebView, url: String?) {
                                pageTitle = view.title ?: url.orEmpty()
                                Diag.log("browser", "loaded ${url.orEmpty().take(120)}")
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                    }
                },
                update = { view ->
                    // Capturing here (instead of a nonexistent onCreated parameter) keeps the
                    // reference out of the composition phase and reloads on address changes.
                    webView = view
                    if (view.url != target) {
                        Diag.log("browser", "open $target")
                        view.loadUrl(target)
                    }
                },
            )
        }
    }
}

/**
 * Accepts a bare filesystem path (preview), a host:port (local backend) or a full URL.
 */
fun normalizeUrl(raw: String): String {
    val text = raw.trim()
    return when {
        text.isEmpty() -> "about:blank"
        text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("file://") || text.startsWith("about:") -> text
        text.startsWith("/") -> "file://$text"
        // "127.0.0.1:8000" / "localhost:8000" are the common backend cases.
        text.contains(":") && !text.contains("/") -> "http://$text"
        text == "localhost" -> "http://127.0.0.1"
        else -> "http://$text"
    }
}
