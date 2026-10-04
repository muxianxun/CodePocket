package com.dsh.codepocket.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * Serves files from app/src/main/assets over https://appassets.androidplatform.net/
 * so the WebView never needs file:// access (which is disabled by default on API 30+).
 */
const val ASSET_DOMAIN = "appassets.androidplatform.net"

/**
 * A WebView that loads one bundled asset page and exposes [jsInterface] to it as
 * `window.<jsInterfaceName>`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AssetWebView(
    assetPath: String,
    jsInterface: Any,
    modifier: Modifier = Modifier,
    jsInterfaceName: String = "Android",
    backgroundColor: Int = Color.parseColor("#1E1E2E"),
    onPageReady: (WebView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(backgroundColor)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.defaultTextEncodingName = "UTF-8"
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true

                addJavascriptInterface(jsInterface, jsInterfaceName)

                webViewClient = object : WebViewClientCompat() {
                    private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        onPageReady(view)
                    }
                }

                loadUrl("https://$ASSET_DOMAIN/assets/$assetPath")
            }
        },
    )
}

/** Escapes a string so it can be embedded in a single-quoted JS string literal. */
fun jsQuote(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (ch in text) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '\'' -> sb.append("\\'")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u2028' -> sb.append("\\u2028")
            '\u2029' -> sb.append("\\u2029")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.toString()
}
