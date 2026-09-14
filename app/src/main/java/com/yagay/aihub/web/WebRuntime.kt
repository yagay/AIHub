package com.yagay.aihub.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.SessionKey
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRuntime(private val context: Context) {
    private val loader = ScriptLoader(context)
    private val webViews = linkedMapOf<String, WebView>()

    val supportsMultiProfile: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun attach(host: FrameLayout, session: SessionKey, provider: ProviderSpec) {
        val webView = obtain(session, provider)
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.removeAllViews()
        host.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (webView.url.isNullOrBlank()) webView.loadUrl(provider.homeUrl)
    }

    suspend fun isLoggedIn(session: SessionKey, provider: ProviderSpec): Boolean {
        ensureLoaded(session, provider)
        return call(session, provider, "isLoggedIn") == "true"
    }

    suspend fun send(session: SessionKey, provider: ProviderSpec, prompt: String): Boolean {
        ensureLoaded(session, provider)
        return call(session, provider, "send", JSONObject.quote(prompt)) == "ok"
    }

    suspend fun lastResponse(session: SessionKey, provider: ProviderSpec): String {
        ensureLoaded(session, provider)
        return call(session, provider, "extractLastResponse").orEmpty()
    }

    suspend fun isGenerating(session: SessionKey, provider: ProviderSpec): Boolean =
        call(session, provider, "isGenerating") == "true"

    suspend fun newChat(session: SessionKey, provider: ProviderSpec) {
        ensureLoaded(session, provider)
        call(session, provider, "newChat")
    }

    suspend fun stop(session: SessionKey, provider: ProviderSpec) {
        call(session, provider, "stop")
    }

    fun destroy() {
        webViews.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        webViews.clear()
    }

    private fun obtain(session: SessionKey, provider: ProviderSpec): WebView {
        val key = if (supportsMultiProfile) session.storageKey else provider.id
        return webViews.getOrPut(key) {
            WebView(context).apply {
                if (supportsMultiProfile) WebViewCompat.setProfile(this, session.webProfileName)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.mediaPlaybackRequiresUserGesture = false
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = handleUri(request.url)
                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUri(Uri.parse(url))
                }
                loadUrl(provider.homeUrl)
            }
        }
    }

    private fun handleUri(uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(true)
    }

    private suspend fun ensureLoaded(session: SessionKey, provider: ProviderSpec) {
        val webView = obtain(session, provider)
        if (webView.url.isNullOrBlank()) webView.loadUrl(provider.homeUrl)
        repeat(40) {
            val state = evalRaw(webView, "document.readyState")
            if (state == "complete" || state == "interactive") return
            delay(250)
        }
    }

    private suspend fun call(session: SessionKey, provider: ProviderSpec, action: String, argumentJs: String? = null): String? {
        val webView = obtain(session, provider)
        val source = loader.providerScript(provider.scriptAsset)
        val invocation = if (argumentJs == null) "$action()" else "$action($argumentJs)"
        val js = """
            (() => {
              try {
                $source
                const value = window.__AIHUB__.$invocation;
                return JSON.stringify({ ok: true, value: value });
              } catch (error) {
                return JSON.stringify({ ok: false, error: String(error) });
              }
            })();
        """.trimIndent()
        val raw = evalRaw(webView, js) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) return null
        val value = obj.opt("value")
        return when (value) {
            null, JSONObject.NULL -> null
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private suspend fun evalRaw(webView: WebView, script: String): String? = suspendCoroutine { continuation ->
        webView.evaluateJavascript(script) { result ->
            if (result == null || result == "null") {
                continuation.resume(null)
            } else {
                val decoded = runCatching { JSONTokener(result).nextValue() }.getOrNull()
                continuation.resume(when (decoded) {
                    null, JSONObject.NULL -> null
                    is String -> decoded
                    else -> decoded.toString().trim('"')
                })
            }
        }
    }
}
