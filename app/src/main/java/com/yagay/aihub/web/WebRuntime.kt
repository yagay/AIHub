package com.yagay.aihub.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.aihub.diagnostics.DiagnosticLogger
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

    init {
        DiagnosticLogger.i("WEB", "runtime_created multiProfile=$supportsMultiProfile")
    }

    val supportsMultiProfile: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun attach(host: FrameLayout, session: SessionKey, provider: ProviderSpec) {
        val webView = obtain(session, provider)
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.removeAllViews()
        host.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (webView.url.isNullOrBlank()) {
            DiagnosticLogger.d("WEB", "load_home provider=${provider.id}")
            webView.loadUrl(provider.homeUrl)
        }
    }

    suspend fun isLoggedIn(session: SessionKey, provider: ProviderSpec): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "isLoggedIn") == "true"
        DiagnosticLogger.d("WEB", "login_check provider=${provider.id} result=$result")
        return result
    }

    suspend fun send(session: SessionKey, provider: ProviderSpec, prompt: String): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "send", JSONObject.quote(prompt))
        DiagnosticLogger.i("WEB", "adapter_send provider=${provider.id} promptChars=${prompt.length} result=${result ?: "null"}")
        return result == "ok"
    }

    suspend fun lastResponse(session: SessionKey, provider: ProviderSpec): String {
        ensureLoaded(session, provider)
        return call(session, provider, "extractLastResponse").orEmpty()
    }

    suspend fun isGenerating(session: SessionKey, provider: ProviderSpec): Boolean =
        call(session, provider, "isGenerating") == "true"

    suspend fun newChat(session: SessionKey, provider: ProviderSpec) {
        ensureLoaded(session, provider)
        val result = call(session, provider, "newChat")
        DiagnosticLogger.i("WEB", "adapter_new_chat provider=${provider.id} result=${result ?: "null"}")
    }

    suspend fun stop(session: SessionKey, provider: ProviderSpec) {
        val result = call(session, provider, "stop")
        DiagnosticLogger.i("WEB", "adapter_stop provider=${provider.id} result=${result ?: "null"}")
    }

    fun destroy() {
        DiagnosticLogger.i("WEB", "runtime_destroy webViews=${webViews.size}")
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
            DiagnosticLogger.i(
                "WEB",
                "webview_create provider=${provider.id} account=${session.accountId.take(12)} multiProfile=$supportsMultiProfile"
            )
            WebView(context).apply {
                if (supportsMultiProfile) {
                    runCatching { WebViewCompat.setProfile(this, session.webProfileName) }
                        .onFailure { DiagnosticLogger.e("WEB", "set_profile_failed provider=${provider.id}", it) }
                }
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

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                            consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                        ) {
                            DiagnosticLogger.w(
                                "CONSOLE",
                                "provider=${provider.id} level=${consoleMessage.messageLevel()} line=${consoleMessage.lineNumber()} source=${safeSource(consoleMessage.sourceId())} message=${DiagnosticLogger.scrub(consoleMessage.message())}"
                            )
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        DiagnosticLogger.i("WEB", "page_started provider=${provider.id} url=${safeUrl(url)}")
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        DiagnosticLogger.i("WEB", "page_finished provider=${provider.id} url=${safeUrl(url)}")
                        super.onPageFinished(view, url)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = handleUri(request.url)

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUri(Uri.parse(url))

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            DiagnosticLogger.e(
                                "WEB",
                                "page_error provider=${provider.id} code=${error.errorCode} description=${DiagnosticLogger.scrub(error.description.toString())} url=${safeUrl(request.url.toString())}"
                            )
                        }
                        super.onReceivedError(view, request, error)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) {
                            DiagnosticLogger.w(
                                "WEB",
                                "http_error provider=${provider.id} status=${errorResponse.statusCode} reason=${DiagnosticLogger.scrub(errorResponse.reasonPhrase.orEmpty())} url=${safeUrl(request.url.toString())}"
                            )
                        }
                        super.onReceivedHttpError(view, request, errorResponse)
                    }
                }
                loadUrl(provider.homeUrl)
            }
        }
    }

    private fun handleUri(uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return false
        DiagnosticLogger.i("WEB", "external_scheme scheme=${uri.scheme.orEmpty()}")
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.onFailure {
            DiagnosticLogger.e("WEB", "external_scheme_failed scheme=${uri.scheme.orEmpty()}", it)
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
        DiagnosticLogger.w("WEB", "document_ready_timeout provider=${provider.id} url=${safeUrl(webView.url)}")
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
        val raw = evalRaw(webView, js)
        if (raw == null) {
            DiagnosticLogger.w("JS", "adapter_null_result provider=${provider.id} action=$action")
            return null
        }
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            DiagnosticLogger.e("JS", "adapter_invalid_json provider=${provider.id} action=$action rawChars=${raw.length}", it)
            return null
        }
        if (!obj.optBoolean("ok", false)) {
            DiagnosticLogger.w(
                "JS",
                "adapter_failed provider=${provider.id} action=$action error=${DiagnosticLogger.scrub(obj.optString("error"))}"
            )
            return null
        }
        val value = obj.opt("value")
        return when (value) {
            null, JSONObject.NULL -> null
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private suspend fun evalRaw(webView: WebView, script: String): String? = suspendCoroutine { continuation ->
        runCatching {
            webView.evaluateJavascript(script) { result ->
                if (result == null || result == "null") {
                    continuation.resume(null)
                } else {
                    val decoded = runCatching { JSONTokener(result).nextValue() }.getOrNull()
                    continuation.resume(
                        when (decoded) {
                            null, JSONObject.NULL -> null
                            is String -> decoded
                            else -> decoded.toString().trim('"')
                        }
                    )
                }
            }
        }.onFailure {
            DiagnosticLogger.e("JS", "evaluate_javascript_failed", it)
            continuation.resume(null)
        }
    }

    private fun safeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<none>"
        return runCatching {
            val uri = Uri.parse(url)
            buildString {
                append(uri.scheme.orEmpty())
                append("://")
                append(uri.host.orEmpty())
                if (uri.port != -1) append(":${uri.port}")
                append(uri.path.orEmpty())
            }
        }.getOrDefault("<invalid-url>")
    }

    private fun safeSource(source: String?): String = safeUrl(source).take(240)
}
