package com.yagay.aihub.web

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.aihub.data.ConversationBindingStore
import com.yagay.aihub.data.PendingAttachmentStore
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.SessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRuntime(private val context: Context) {
    data class AttachmentAttachResult(
        val attachedCount: Int,
        val names: List<String>,
        val failure: String? = null
    )

    data class ResponseSnapshot(
        val text: String = "",
        val key: String = "",
        val source: String = "none",
        val responseCount: Int = 0,
        val turnCount: Int = 0,
        val state: String = "idle",
        val reason: String = "",
        val path: String = "",
        val quietMs: Long = -1L
    ) {
        val isGenerating: Boolean
            get() = state == "generating" || state == "queued" || state == "uploading"
    }

    private val loader = ScriptLoader(context)
    private val bindingStore = ConversationBindingStore(context.applicationContext)
    private val pendingAttachmentStore = PendingAttachmentStore(context.applicationContext)
    private val webViews = linkedMapOf<String, WebView>()
    private val attachmentBridges = linkedMapOf<String, NativeAttachmentBridge>()
    private val restoredKeys = mutableSetOf<String>()
    private val preferredUrls = mutableMapOf<String, String>()
    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var pageChangeListener: ((SessionKey, ProviderSpec, String) -> Unit)? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileProvider: String? = null

    init {
        DiagnosticLogger.i("WEB", "runtime_created multiProfile=$supportsMultiProfile")
    }

    val supportsMultiProfile: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
        DiagnosticLogger.d("FILE", "file_chooser_launcher_set available=${launcher != null}")
    }

    fun setPageChangeListener(listener: ((SessionKey, ProviderSpec, String) -> Unit)?) {
        pageChangeListener = listener
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = pendingFileCallback ?: return
        val provider = pendingFileProvider.orEmpty()
        val uris = if (resultCode == Activity.RESULT_OK) {
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
        } else null

        DiagnosticLogger.i(
            "FILE",
            "file_chooser_result provider=$provider resultCode=$resultCode selected=${uris?.size ?: 0}"
        )
        callback.onReceiveValue(uris)
        pendingFileCallback = null
        pendingFileProvider = null
    }

    fun attach(
        host: FrameLayout,
        session: SessionKey,
        provider: ProviderSpec,
        preferredUrl: String? = null
    ) {
        val key = webViewKey(session, provider)
        val restored = sameOriginUrl(preferredUrl, provider.homeUrl)
            ?: sameOriginUrl(bindingStore.loadUrl(session), provider.homeUrl)
        if (restored != null) preferredUrls[key] = restored

        val webView = obtain(session, provider)
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.removeAllViews()
        host.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        if (!restoredKeys.contains(key)) {
            restoredKeys += key
            val target = preferredUrls[key] ?: provider.homeUrl
            DiagnosticLogger.i(
                "WEB",
                "initial_load provider=${provider.id} restored=${preferredUrls[key] != null} url=${safeUrl(target)}"
            )
            webView.loadUrl(target)
        } else if (webView.url.isNullOrBlank()) {
            webView.loadUrl(preferredUrls[key] ?: provider.homeUrl)
        }
    }

    fun currentUrl(session: SessionKey, provider: ProviderSpec): String? =
        webViews[webViewKey(session, provider)]?.url

    suspend fun isLoggedIn(session: SessionKey, provider: ProviderSpec): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "isLoggedIn") == "true"
        DiagnosticLogger.d("WEB", "login_check provider=${provider.id} result=$result")
        return result
    }

    suspend fun attachFiles(
        session: SessionKey,
        provider: ProviderSpec,
        uris: List<Uri>
    ): AttachmentAttachResult {
        if (uris.isEmpty()) {
            pendingAttachmentStore.clear(session)
            return AttachmentAttachResult(0, emptyList(), "no-selection")
        }
        ensureLoaded(session, provider)
        val key = webViewKey(session, provider)
        val bridge = attachmentBridges[key]
            ?: run {
                pendingAttachmentStore.clear(session)
                return AttachmentAttachResult(0, emptyList(), "bridge-unavailable")
            }

        val staged = runCatching {
            withContext(Dispatchers.IO) { bridge.stage(uris) }
        }.onFailure {
            DiagnosticLogger.e("FILE", "attachment_stage_failed provider=${provider.id}", it)
        }.getOrElse {
            pendingAttachmentStore.clear(session)
            return AttachmentAttachResult(0, emptyList(), "stage-failed")
        }

        DiagnosticLogger.i(
            "FILE",
            "attachment_stage_ready provider=${provider.id} selected=${staged.names.size} totalBytes=${staged.totalBytes}"
        )
        if (staged.names.isEmpty()) {
            bridge.clearNative()
            pendingAttachmentStore.clear(session)
            return AttachmentAttachResult(0, emptyList(), "empty-stage")
        }

        var result = call(session, provider, "attachStagedFiles")
        if (result == "no-input") {
            val prepared = call(session, provider, "prepareAttachmentInput")
            DiagnosticLogger.d(
                "FILE",
                "attachment_prepare_input provider=${provider.id} result=${prepared ?: "null"}"
            )
            delay(350)
            result = call(session, provider, "attachStagedFiles")
            if (result == "no-input") {
                delay(550)
                result = call(session, provider, "attachStagedFiles")
            }
        }

        val attachedCount = result
            ?.takeIf { it.startsWith("attached:") }
            ?.substringAfter(':')
            ?.toIntOrNull()
            ?: 0

        val probe = call(session, provider, "attachmentProbe").orEmpty()
        if (attachedCount > 0) {
            val attachedMetadata = staged.attachments.take(attachedCount)
            val attachedNames = attachedMetadata.map { it.name }
            pendingAttachmentStore.save(session, attachedMetadata)
            DiagnosticLogger.i(
                "FILE",
                "attachment_injected provider=${provider.id} attached=$attachedCount selected=${staged.names.size} metadata=${attachedMetadata.size} probe=${DiagnosticLogger.scrub(probe).take(800)}"
            )
            bridge.clearNative()
            return AttachmentAttachResult(attachedCount, attachedNames)
        }

        DiagnosticLogger.w(
            "FILE",
            "attachment_injection_failed provider=${provider.id} result=${result ?: "null"} probe=${DiagnosticLogger.scrub(probe).take(1000)}"
        )
        bridge.clearNative()
        pendingAttachmentStore.clear(session)
        return AttachmentAttachResult(0, emptyList(), result ?: "null-result")
    }

    suspend fun openAttachmentPicker(session: SessionKey, provider: ProviderSpec): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "openAttachmentPicker")
        DiagnosticLogger.i(
            "FILE",
            "attachment_picker_requested provider=${provider.id} result=${result ?: "null"}"
        )
        if (result == "opened-input" || result == "opened-button") return true

        val probe = call(session, provider, "attachmentProbe").orEmpty()
        DiagnosticLogger.w(
            "FILE",
            "attachment_picker_unavailable provider=${provider.id} probe=${DiagnosticLogger.scrub(probe).take(1200)}"
        )
        return false
    }

    suspend fun send(session: SessionKey, provider: ProviderSpec, prompt: String): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "send", JSONObject.quote(prompt))
        DiagnosticLogger.i(
            "WEB",
            "adapter_send provider=${provider.id} promptChars=${prompt.length} result=${result ?: "null"}"
        )

        if (result == "ok") return true
        if (result != "verify" && result != "queued") return false

        val maxAttempts = if (result == "queued") 72 else 24
        for (attempt in 0 until maxAttempts) {
            delay(220)
            val acknowledged = call(session, provider, "submissionAcknowledged") == "true"
            if (acknowledged) {
                DiagnosticLogger.i(
                    "WEB",
                    "adapter_send_verified provider=${provider.id} result=$result attempts=${attempt + 1}"
                )
                return true
            }

            if (attempt == 0 || attempt == 7 || attempt == 23 || attempt == maxAttempts - 1) {
                val status = call(session, provider, "submissionStatus").orEmpty()
                DiagnosticLogger.d(
                    "WEB",
                    "send_verification_pending provider=${provider.id} result=$result attempt=${attempt + 1}/$maxAttempts status=${DiagnosticLogger.scrub(status).take(900)}"
                )
                if (status.contains("attachment-button-timeout")) break
            }
        }

        val status = call(session, provider, "submissionStatus").orEmpty()
        val probe = call(session, provider, "probeSummary").orEmpty()
        DiagnosticLogger.w(
            "WEB",
            "send_verification_failed provider=${provider.id} result=$result status=${DiagnosticLogger.scrub(status).take(900)} probe=${DiagnosticLogger.scrub(probe).take(1400)}"
        )
        return false
    }

    suspend fun responseSnapshot(session: SessionKey, provider: ProviderSpec): ResponseSnapshot {
        ensureLoaded(session, provider)
        val raw = call(session, provider, "generationState").orEmpty()
        return parseResponseSnapshot(raw)
    }

    suspend fun lastResponse(session: SessionKey, provider: ProviderSpec): String =
        responseSnapshot(session, provider).text

    suspend fun isGenerating(session: SessionKey, provider: ProviderSpec): Boolean =
        responseSnapshot(session, provider).isGenerating

    suspend fun probeSummary(session: SessionKey, provider: ProviderSpec): String {
        ensureLoaded(session, provider)
        return call(session, provider, "probeSummary").orEmpty()
    }

    suspend fun capabilities(session: SessionKey, provider: ProviderSpec): ProviderCapabilities {
        ensureLoaded(session, provider)
        val result = ProviderCapabilities.fromJson(call(session, provider, "capabilities"))
        DiagnosticLogger.d(
            "CAP",
            "snapshot provider=${provider.id} model=${result.model} search=${result.search} reasoning=${result.reasoning} research=${result.deepResearch} image=${result.imageGeneration} tools=${result.tools} retry=${result.retry} continue=${result.continueGeneration} history=${result.history} voice=${result.voice}"
        )
        return result
    }

    suspend fun optionList(session: SessionKey, provider: ProviderSpec, kind: String): List<String> {
        ensureLoaded(session, provider)
        val raw = call(session, provider, "optionList", JSONObject.quote(kind)).orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }.distinct().take(80)
    }

    suspend fun openOptionPicker(session: SessionKey, provider: ProviderSpec, kind: String): String {
        ensureLoaded(session, provider)
        val result = call(session, provider, "openOptionPicker", JSONObject.quote(kind)).orEmpty()
        DiagnosticLogger.i(
            "CAP",
            "open_option_picker provider=${provider.id} kind=$kind result=${result.ifBlank { "null" }}"
        )
        return result
    }

    suspend fun selectOption(
        session: SessionKey,
        provider: ProviderSpec,
        kind: String,
        value: String
    ): String {
        ensureLoaded(session, provider)
        val args = "${JSONObject.quote(kind)},${JSONObject.quote(value)}"
        val result = call(session, provider, "selectOption", args).orEmpty()
        DiagnosticLogger.i(
            "CAP",
            "select_option provider=${provider.id} kind=$kind valueChars=${value.length} result=${result.ifBlank { "null" }}"
        )
        return result
    }

    suspend fun performAction(
        session: SessionKey,
        provider: ProviderSpec,
        action: String,
        value: String? = null
    ): String {
        ensureLoaded(session, provider)
        val args = "${JSONObject.quote(action)},${JSONObject.quote(value.orEmpty())}"
        val result = call(session, provider, "performAction", args).orEmpty()
        DiagnosticLogger.i(
            "CAP",
            "perform_action provider=${provider.id} action=$action valueChars=${value?.length ?: 0} result=${result.ifBlank { "null" }}"
        )
        return result
    }

    suspend fun newChat(session: SessionKey, provider: ProviderSpec) {
        bindingStore.clear(session)
        pendingAttachmentStore.clear(session)
        preferredUrls.remove(webViewKey(session, provider))
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
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingFileProvider = null
        pageChangeListener = null
        attachmentBridges.values.forEach { it.clearNative() }
        attachmentBridges.clear()
        webViews.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching { webView.removeJavascriptInterface("AIHubNativeFiles") }
            webView.stopLoading()
            webView.destroy()
        }
        webViews.clear()
        restoredKeys.clear()
        preferredUrls.clear()
    }

    private fun webViewKey(session: SessionKey, provider: ProviderSpec): String =
        if (supportsMultiProfile) session.storageKey else provider.id

    private fun obtain(session: SessionKey, provider: ProviderSpec): WebView {
        val key = webViewKey(session, provider)
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

                val nativeAttachmentBridge = NativeAttachmentBridge(context.applicationContext)
                attachmentBridges[key] = nativeAttachmentBridge
                addJavascriptInterface(nativeAttachmentBridge, "AIHubNativeFiles")

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
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val allowed = request.resources.filter { resource ->
                            when (resource) {
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                else -> false
                            }
                        }
                        DiagnosticLogger.i(
                            "WEB",
                            "permission_request provider=${provider.id} requested=${request.resources.joinToString("|").take(240)} granted=${allowed.joinToString("|").take(240)}"
                        )
                        if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        val launcher = fileChooserLauncher
                        if (launcher == null) {
                            DiagnosticLogger.w("FILE", "file_chooser_no_launcher provider=${provider.id}")
                            filePathCallback.onReceiveValue(null)
                            return false
                        }

                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = filePathCallback
                        pendingFileProvider = provider.id

                        val intent = runCatching { fileChooserParams.createIntent() }.getOrElse {
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = fileChooserParams.acceptTypes
                                    .firstOrNull { it.isNotBlank() }
                                    ?.takeIf { !it.startsWith(".") }
                                    ?: "*/*"
                            }
                        }.apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            }
                        }

                        DiagnosticLogger.i(
                            "FILE",
                            "file_chooser_open provider=${provider.id} mode=${fileChooserParams.mode} accepts=${fileChooserParams.acceptTypes.filter { it.isNotBlank() }.joinToString("|").take(240)}"
                        )
                        return runCatching {
                            launcher(intent)
                            true
                        }.onFailure {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = null
                            pendingFileProvider = null
                            DiagnosticLogger.e("FILE", "file_chooser_launch_failed provider=${provider.id}", it)
                        }.getOrDefault(false)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        if (
                            consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
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
                        val sameOrigin = sameOriginUrl(url, provider.homeUrl)
                        if (sameOrigin != null) {
                            preferredUrls[key] = sameOrigin
                            bindingStore.saveUrl(session, sameOrigin)
                            pageChangeListener?.invoke(session, provider, sameOrigin)
                        }
                        super.onPageFinished(view, url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = handleUri(request.url)

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        handleUri(Uri.parse(url))

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            DiagnosticLogger.e(
                                "WEB",
                                "page_error provider=${provider.id} code=${error.errorCode} description=${DiagnosticLogger.scrub(error.description.toString())} url=${safeUrl(request.url.toString())}"
                            )
                        }
                        super.onReceivedError(view, request, error)
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            DiagnosticLogger.w(
                                "WEB",
                                "http_error provider=${provider.id} status=${errorResponse.statusCode} reason=${DiagnosticLogger.scrub(errorResponse.reasonPhrase.orEmpty())} url=${safeUrl(request.url.toString())}"
                            )
                        }
                        super.onReceivedHttpError(view, request, errorResponse)
                    }
                }
            }
        }
    }

    private fun handleUri(uri: Uri): Boolean {
        if (uri.scheme == "http" || uri.scheme == "https") return false
        DiagnosticLogger.i("WEB", "external_scheme scheme=${uri.scheme.orEmpty()}")
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure {
            DiagnosticLogger.e("WEB", "external_scheme_failed scheme=${uri.scheme.orEmpty()}", it)
        }.getOrDefault(true)
    }

    private suspend fun ensureLoaded(session: SessionKey, provider: ProviderSpec) {
        val key = webViewKey(session, provider)
        val webView = obtain(session, provider)
        if (webView.url.isNullOrBlank()) {
            val restored = sameOriginUrl(bindingStore.loadUrl(session), provider.homeUrl)
            if (restored != null) preferredUrls[key] = restored
            webView.loadUrl(preferredUrls[key] ?: provider.homeUrl)
        }
        repeat(40) {
            val state = evalRaw(webView, "document.readyState")
            if (state == "complete" || state == "interactive") return
            delay(250)
        }
        DiagnosticLogger.w(
            "WEB",
            "document_ready_timeout provider=${provider.id} url=${safeUrl(webView.url)}"
        )
    }

    private suspend fun call(
        session: SessionKey,
        provider: ProviderSpec,
        action: String,
        argumentJs: String? = null
    ): String? {
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
            DiagnosticLogger.e(
                "JS",
                "adapter_invalid_json provider=${provider.id} action=$action rawChars=${raw.length}",
                it
            )
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

    private suspend fun evalRaw(webView: WebView, script: String): String? =
        suspendCoroutine { continuation ->
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

    private fun parseResponseSnapshot(raw: String): ResponseSnapshot {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ResponseSnapshot()
        return ResponseSnapshot(
            text = obj.optString("text"),
            key = obj.optString("key"),
            source = obj.optString("source", "none"),
            responseCount = obj.optInt("responseCount", 0),
            turnCount = obj.optInt("turnCount", 0),
            state = obj.optString("state", "idle"),
            reason = obj.optString("reason"),
            path = obj.optString("pathHash"),
            quietMs = obj.optLong("quietMs", -1L)
        )
    }

    private fun sameOriginUrl(raw: String?, homeUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val target = Uri.parse(raw)
            val home = Uri.parse(homeUrl)
            if (target.scheme != "https" && target.scheme != "http") return@runCatching null
            if (!target.host.equals(home.host, ignoreCase = true)) return@runCatching null
            target.buildUpon().clearQuery().fragment(null).build().toString()
        }.getOrNull()
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
                append(redactPath(uri.path.orEmpty()))
            }
        }.getOrDefault("<invalid-url>")
    }

    private fun redactPath(path: String): String {
        if (path.isBlank() || path == "/") return path
        return path.split('/').joinToString("/") { segment ->
            when {
                segment.length >= 18 && segment.matches(Regex("[A-Za-z0-9_-]+")) -> "<id>"
                segment.length >= 16 && segment.matches(Regex("[0-9a-fA-F-]+")) -> "<id>"
                else -> segment
            }
        }
    }

    private fun safeSource(source: String?): String = safeUrl(source).take(240)
}
