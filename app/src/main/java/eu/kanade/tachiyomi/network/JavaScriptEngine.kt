package eu.kanade.tachiyomi.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用系统 WebView 执行 JS，供扩展里的 JavaScriptEngine 调用。
 * 结果统一按 JSON 解析后转成 String/Int/Double/Boolean。
 */
@SuppressLint("SetJavaScriptEnabled")
class JavaScriptEngine(private val context: Context) {

    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Main) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        try {
            suspendCancellableCoroutine { cont ->
                var finished = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (finished) return
                        finished = true
                        view.evaluateJavascript(script) { raw ->
                            runCatching { parseJsResult(raw) }
                                .onSuccess { cont.resume(it as T) }
                                .onFailure { cont.resumeWithException(it) }
                        }
                    }
                }
                webView.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
                cont.invokeOnCancellation { runCatching { webView.destroy() } }
            }
        } finally {
            runCatching { webView.destroy() }
        }
    }

    private fun parseJsResult(raw: String?): Any? {
        if (raw == null || raw == "null") return null
        val text = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            JSONObject("{\"v\":$raw}").getString("v")
        } else raw
        return when {
            text == "true" -> true
            text == "false" -> false
            text.toLongOrNull() != null -> text.toLong()
            text.toDoubleOrNull() != null -> text.toDouble()
            else -> text
        }
    }
}
