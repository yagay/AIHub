package com.yagay.aihub.web

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.aihub.data.ConversationBindingStore
import com.yagay.aihub.data.PendingAttachmentStore
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.SessionKey
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRuntime(private val context: Context) {
    private data class BinaryDownloadMeta(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long
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
    private val restoredKeys = mutableSetOf<String>()
    private val preferredUrls = mutableMapOf<String, String>()
    private val runtimeInjectedKeys = mutableSetOf<String>()
    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var fileSelectionListener: ((SessionKey, ProviderSpec, List<AttachmentMeta>) -> Unit)? = null
    private var pageChangeListener: ((SessionKey, ProviderSpec, String) -> Unit)? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileSession: SessionKey? = null
    private var pendingFileProvider: ProviderSpec? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingWebPermissionResources: Array<String> = emptyArray()
    private val pendingBinaryDownloads = mutableMapOf<String, BinaryDownloadMeta>()

    init {
        DiagnosticLogger.i("WEB", "runtime_created multiProfile=$supportsMultiProfile")
    }

    val supportsMultiProfile: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
        DiagnosticLogger.d("FILE", "file_chooser_launcher_set available=${launcher != null}")
    }

    fun setFileSelectionListener(listener: ((SessionKey, ProviderSpec, List<AttachmentMeta>) -> Unit)?) {
        fileSelectionListener = listener
    }

    fun setPageChangeListener(listener: ((SessionKey, ProviderSpec, String) -> Unit)?) {
        pageChangeListener = listener
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = pendingFileCallback ?: return
        val session = pendingFileSession
        val provider = pendingFileProvider
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
            "file_chooser_result provider=${provider?.id.orEmpty()} resultCode=$resultCode selected=${uris?.size ?: 0}"
        )

        callback.onReceiveValue(uris)

        if (session != null && provider != null && !uris.isNullOrEmpty()) {
            val attachments = uris.mapIndexed { index, uri -> queryAttachmentMeta(uri, index) }
            val webView = webViews[webViewKey(session, provider)]
            if (webView != null) {
                confirmFileSelection(webView, session, provider, attachments, 0)
            } else {
                DiagnosticLogger.w("FILE", "file_chooser_confirmation_missing_webview provider=${provider.id}")
            }
        }

        pendingFileCallback = null
        pendingFileSession = null
        pendingFileProvider = null
        flushCookies()
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
        if (webView.parent !== host || host.childCount != 1 || host.getChildAt(0) !== webView) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            host.removeAllViews()
            host.addView(
                webView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

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

    suspend fun openAttachmentPicker(session: SessionKey, provider: ProviderSpec): Boolean {
        ensureLoaded(session, provider)
        val result = call(session, provider, "openAttachmentPicker")
        DiagnosticLogger.i(
            "FILE",
            "attachment_picker_requested provider=${provider.id} result=${result ?: "null"}"
        )
        if (result == "opened-input" || result == "opened-button" || result == "scheduled") return true

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

    fun canGoBack(session: SessionKey, provider: ProviderSpec): Boolean =
        webViews[webViewKey(session, provider)]?.canGoBack() == true

    fun goBack(session: SessionKey, provider: ProviderSpec): Boolean {
        val webView = webViews[webViewKey(session, provider)] ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun flushCookies() {
        val managers = webViews.values.map { cookieManagerFor(it) }.toSet()
        val targets = if (managers.isEmpty()) setOf(CookieManager.getInstance()) else managers
        targets.forEach { manager ->
            runCatching { manager.flush() }
                .onFailure { DiagnosticLogger.e("WEB", "cookie_flush_failed", it) }
        }
    }

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != WEB_PERMISSION_REQUEST_CODE) return false
        val request = pendingWebPermissionRequest
        val resources = pendingWebPermissionResources
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()
        if (request == null) return true

        val allowed = resources.filter { resource ->
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
            "permission_result permissions=${permissions.joinToString("|").take(240)} grants=${grantResults.joinToString("|")} allowed=${allowed.joinToString("|").take(240)}"
        )
        if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
        return true
    }

    fun markAttachmentsSubmitted(session: SessionKey, provider: ProviderSpec) {
        pendingAttachmentStore.clear(session)
        webViews[webViewKey(session, provider)]?.evaluateJavascript(
            "if(window.__AIHUB_ATTACHMENT_STATE__){window.__AIHUB_ATTACHMENT_STATE__.lastAttachedCount=0;window.__AIHUB_ATTACHMENT_STATE__.lastAttachedAt=0;}",
            null
        )
        fileSelectionListener?.invoke(session, provider, emptyList())
    }

    fun resetSession(session: SessionKey, provider: ProviderSpec) {
        val key = webViewKey(session, provider)
        val webView = webViews[key] ?: obtain(session, provider)
        val policy = ProviderWebPolicies.forProvider(provider)

        bindingStore.clear(session)
        pendingAttachmentStore.clear(session)
        preferredUrls.remove(key)
        runtimeInjectedKeys.remove(key)
        fileSelectionListener?.invoke(session, provider, emptyList())

        webView.stopLoading()
        webView.clearFormData()
        webView.clearHistory()
        webView.clearMatches()
        webView.clearSslPreferences()

        val cookieManager = cookieManagerFor(webView)
        val finishReset = {
            webView.post {
                runCatching {
                    if (supportsMultiProfile) {
                        WebViewCompat.getProfile(webView).webStorage.deleteAllData()
                    } else {
                        policy.primaryHosts.forEach { host ->
                            android.webkit.WebStorage.getInstance().deleteOrigin("https://$host")
                        }
                    }
                }.onFailure {
                    DiagnosticLogger.e("WEB", "web_storage_reset_failed provider=${provider.id}", it)
                }
                cookieManager.flush()
                restoredKeys += key
                webView.loadUrl(provider.homeUrl)
                DiagnosticLogger.i("WEB", "session_reset provider=${provider.id} account=${session.accountId.take(12)}")
            }
        }

        if (supportsMultiProfile) {
            cookieManager.removeAllCookies { finishReset() }
        } else {
            clearProviderCookies(cookieManager, policy)
            webView.postDelayed({ finishReset() }, 120L)
        }
    }

    fun destroy() {
        DiagnosticLogger.i("WEB", "runtime_destroy webViews=${webViews.size}")
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingFileSession = null
        pendingFileProvider = null
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        pendingWebPermissionResources = emptyArray()
        pendingBinaryDownloads.clear()
        fileSelectionListener = null
        pageChangeListener = null
        flushCookies()
        webViews.values.forEach { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        webViews.clear()
        restoredKeys.clear()
        preferredUrls.clear()
        runtimeInjectedKeys.clear()
    }

    private fun webViewKey(session: SessionKey, provider: ProviderSpec): String =
        if (supportsMultiProfile) session.storageKey else provider.id

    private fun obtain(session: SessionKey, provider: ProviderSpec): WebView {
        val key = webViewKey(session, provider)
        val policy = ProviderWebPolicies.forProvider(provider)
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
                settings.databaseEnabled = false
                settings.loadsImagesAutomatically = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.saveFormData = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.mediaPlaybackRequiresUserGesture = false
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.userAgentString = compatibleUserAgent(settings.userAgentString, policy.userAgentMode)
                val profileCookies = cookieManagerFor(this)
                profileCookies.setAcceptCookie(true)
                profileCookies.setAcceptThirdPartyCookies(this, policy.acceptThirdPartyCookies)
                installBinaryDownloadBridge(this, key, provider, policy)

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        handleWebPermissionRequest(provider, request)
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest) {
                        if (pendingWebPermissionRequest === request) {
                            pendingWebPermissionRequest = null
                            pendingWebPermissionResources = emptyArray()
                        }
                        super.onPermissionRequestCanceled(request)
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
                        pendingFileSession = session
                        pendingFileProvider = provider
                        webView.evaluateJavascript(
                            "if(window.__AIHUB_ATTACHMENT_STATE__){window.__AIHUB_ATTACHMENT_STATE__.lastAttachedCount=0;window.__AIHUB_ATTACHMENT_STATE__.lastAttachedAt=0;}",
                            null
                        )

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
                            "file_chooser_open provider=${provider.id} account=${session.accountId.take(12)} mode=${fileChooserParams.mode} accepts=${fileChooserParams.acceptTypes.filter { it.isNotBlank() }.joinToString("|").take(240)}"
                        )
                        return runCatching {
                            launcher(intent)
                            true
                        }.onFailure {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = null
                            pendingFileSession = null
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
                        runtimeInjectedKeys.remove(key)
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
                    ): Boolean = handleUri(provider, request.url, request.isForMainFrame)

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        handleUri(provider, Uri.parse(url), true)

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

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    enqueueDownload(
                        webView = this,
                        key = key,
                        provider = provider,
                        policy = policy,
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType
                    )
                }
            }
        }
    }

    private fun handleUri(provider: ProviderSpec, uri: Uri, isMainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https") {
            if (!isMainFrame) return false
            val policy = ProviderWebPolicies.forProvider(provider)
            if (policy.allowsTopLevel(uri)) return false

            DiagnosticLogger.i(
                "WEB",
                "external_link provider=${provider.id} host=${uri.host.orEmpty()}"
            )
            return openExternal(uri)
        }

        DiagnosticLogger.i("WEB", "external_scheme provider=${provider.id} scheme=$scheme")
        return openExternal(uri)
    }

    private fun openExternal(uri: Uri): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.onFailure {
        DiagnosticLogger.e("WEB", "external_open_failed scheme=${uri.scheme.orEmpty()}", it)
    }.getOrDefault(true)

    private fun handleWebPermissionRequest(provider: ProviderSpec, request: PermissionRequest) {
        val supported = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        if (supported.isEmpty()) {
            DiagnosticLogger.w(
                "WEB",
                "permission_request_denied provider=${provider.id} reason=unsupported resources=${request.resources.joinToString("|").take(240)}"
            )
            request.deny()
            return
        }

        val missingPermissions = supported.mapNotNull { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
        }.distinct().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            request.grant(supported.toTypedArray())
            DiagnosticLogger.i(
                "WEB",
                "permission_request provider=${provider.id} granted=${supported.joinToString("|").take(240)}"
            )
            return
        }

        val activity = context as? Activity
        if (activity == null) {
            request.deny()
            DiagnosticLogger.w("WEB", "permission_request_denied provider=${provider.id} reason=no-activity")
            return
        }

        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = request
        pendingWebPermissionResources = supported.toTypedArray()
        DiagnosticLogger.i(
            "WEB",
            "permission_request_android provider=${provider.id} permissions=${missingPermissions.joinToString("|")}"
        )
        ActivityCompat.requestPermissions(
            activity,
            missingPermissions.toTypedArray(),
            WEB_PERMISSION_REQUEST_CODE
        )
    }

    private fun confirmFileSelection(
        webView: WebView,
        session: SessionKey,
        provider: ProviderSpec,
        attachments: List<AttachmentMeta>,
        attempt: Int
    ) {
        val delayMs = FILE_CONFIRM_DELAYS.getOrElse(attempt) { FILE_CONFIRM_DELAYS.last() }
        webView.postDelayed({
            if (webView.url.isNullOrBlank()) return@postDelayed
            val js = """
                (() => {
                  try {
                    const p = window.__AIHUB__?.attachmentProbe?.();
                    if (!p) return 0;
                    const fresh = Number(p.lastAttachedAgeMs ?? -1) >= 0 &&
                      Number(p.lastAttachedAgeMs ?? -1) < 8000;
                    if (fresh) return Number(p.lastAttachedCount || 0);
                    return Number(p.liveFilesCount || 0);
                  } catch (_) { return 0; }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js) { raw ->
                val count = raw?.trim()?.trim('"')?.toIntOrNull() ?: 0
                if (count > 0) {
                    val accepted = attachments.take(minOf(count, attachments.size))
                    pendingAttachmentStore.save(session, accepted)
                    fileSelectionListener?.invoke(session, provider, accepted)
                    DiagnosticLogger.i(
                        "FILE",
                        "file_chooser_confirmed provider=${provider.id} selected=${attachments.size} confirmed=${accepted.size}"
                    )
                } else if (attempt + 1 < FILE_CONFIRM_DELAYS.size) {
                    confirmFileSelection(webView, session, provider, attachments, attempt + 1)
                } else {
                    DiagnosticLogger.w(
                        "FILE",
                        "file_chooser_not_confirmed provider=${provider.id} selected=${attachments.size}"
                    )
                    Toast.makeText(context, "${provider.name} 没有确认附件，请在官网页面检查。", Toast.LENGTH_SHORT).show()
                }
            }
        }, delayMs)
    }

    private fun clearProviderCookies(cookieManager: CookieManager, policy: ProviderWebPolicy) {
        policy.primaryHosts.forEach { host ->
            val url = "https://$host/"
            val names = cookieManager.getCookie(url)
                ?.split(';')
                ?.mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotBlank() } }
                ?.distinct()
                .orEmpty()
            names.forEach { name ->
                cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
            }
        }
        cookieManager.flush()
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
        ensureLoaded(session, provider)
        val webView = obtain(session, provider)
        if (!ensureRuntimeInjected(session, provider, webView)) return null

        val invocation = if (argumentJs == null) "$action()" else "$action($argumentJs)"
        val js = """
            (() => {
              try {
                const api = window.__AIHUB__;
                if (!api || typeof api.$action !== "function") {
                  return JSON.stringify({ ok: false, error: "action-unavailable" });
                }
                const value = api.$invocation;
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

    private suspend fun ensureRuntimeInjected(
        session: SessionKey,
        provider: ProviderSpec,
        webView: WebView
    ): Boolean {
        val key = webViewKey(session, provider)
        if (key in runtimeInjectedKeys) {
            val stillPresent = evalRaw(
                webView,
                "typeof window.__AIHUB__ === 'object' && typeof window.__AIHUB__.generationState === 'function'"
            ) == "true"
            if (stillPresent) return true
            runtimeInjectedKeys.remove(key)
        }

        val source = loader.providerScript(provider.scriptAsset)
        val result = evalRaw(
            webView,
            """
                (() => {
                  try {
                    $source
                    return typeof window.__AIHUB__ === "object" &&
                      typeof window.__AIHUB__.generationState === "function";
                  } catch (error) {
                    console.error("AIHub runtime injection failed", error);
                    return false;
                  }
                })();
            """.trimIndent()
        )
        val ready = result == "true"
        if (ready) {
            runtimeInjectedKeys += key
            DiagnosticLogger.i(
                "JS",
                "runtime_injected provider=${provider.id} account=${session.accountId.take(12)} bytes=${source.length}"
            )
        } else {
            DiagnosticLogger.w(
                "JS",
                "runtime_injection_failed provider=${provider.id} account=${session.accountId.take(12)} result=${result ?: "null"}"
            )
        }
        return ready
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

    private fun enqueueDownload(
        webView: WebView,
        key: String,
        provider: ProviderSpec,
        policy: ProviderWebPolicy,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        when (scheme) {
            "http", "https" -> enqueueHttpDownload(webView, uri!!, url, userAgent, contentDisposition, mimeType)
            "data" -> enqueueDataDownload(url, contentDisposition, mimeType)
            "blob" -> enqueueBlobDownload(webView, key, provider, policy, url, contentDisposition, mimeType)
            else -> {
                DiagnosticLogger.w("DOWNLOAD", "unsupported_download_url url=${safeUrl(url)}")
                Toast.makeText(context, "当前下载链接类型暂不支持", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueHttpDownload(
        webView: WebView,
        uri: Uri,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (uri.host.isNullOrBlank()) {
            DiagnosticLogger.w("DOWNLOAD", "download_missing_host")
            return
        }
        val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        runCatching {
            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                setDescription("AIHub download")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                val cookie = cookieManagerFor(webView).getCookie(url)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                val ua = userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString
                if (!ua.isNullOrBlank()) addRequestHeader("User-Agent", ua)
                webView.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { addRequestHeader("Referer", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                }
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            DiagnosticLogger.i(
                "DOWNLOAD",
                "download_enqueued id=$id file=${DiagnosticLogger.scrub(fileName, 180)} mime=${mimeType.orEmpty()}"
            )
            Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            DiagnosticLogger.e("DOWNLOAD", "download_enqueue_failed file=${DiagnosticLogger.scrub(fileName, 180)}", it)
            Toast.makeText(context, "下载失败：$fileName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDataDownload(
        url: String,
        contentDisposition: String?,
        fallbackMimeType: String?
    ) {
        runCatching {
            val comma = url.indexOf(',')
            require(comma > 4) { "invalid-data-url" }
            val meta = url.substring(5, comma)
            val encoded = url.substring(comma + 1)
            val base64 = meta.split(';').any { it.equals("base64", ignoreCase = true) }
            val mime = meta.substringBefore(';').takeIf { it.contains('/') }
                ?: fallbackMimeType
                ?: "application/octet-stream"
            val bytes = if (base64) {
                Base64.decode(encoded, Base64.DEFAULT)
            } else {
                Uri.decode(encoded).toByteArray(Charsets.UTF_8)
            }
            require(bytes.size <= MAX_BINARY_DOWNLOAD_BYTES) { "data-download-too-large" }
            val fileName = sanitizeFileName(URLUtil.guessFileName("download", contentDisposition, mime))
            saveBinaryDownload(bytes, fileName, mime)
        }.onFailure {
            DiagnosticLogger.e("DOWNLOAD", "data_download_failed", it)
            Toast.makeText(context, "数据下载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueBlobDownload(
        webView: WebView,
        key: String,
        provider: ProviderSpec,
        policy: ProviderWebPolicy,
        blobUrl: String,
        contentDisposition: String?,
        fallbackMimeType: String?
    ) {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        ) {
            DiagnosticLogger.w("DOWNLOAD", "blob_download_unsupported provider=${provider.id}")
            Toast.makeText(context, "当前 Android WebView 不支持 Blob 下载", Toast.LENGTH_SHORT).show()
            return
        }

        val suggested = sanitizeFileName(
            URLUtil.guessFileName("download", contentDisposition, fallbackMimeType)
        )
        val script = """
            (() => {
              (async () => {
                try {
                  const response = await fetch(${JSONObject.quote(blobUrl)});
                  const blob = await response.blob();
                  if (blob.size > $MAX_BINARY_DOWNLOAD_BYTES) {
                    AIHubBinaryDownload.postMessage(JSON.stringify({
                      type: "error", reason: "too-large", size: blob.size
                    }));
                    return;
                  }
                  AIHubBinaryDownload.postMessage(JSON.stringify({
                    type: "meta",
                    fileName: ${JSONObject.quote(suggested)},
                    mimeType: blob.type || ${JSONObject.quote(fallbackMimeType ?: "application/octet-stream")},
                    size: blob.size
                  }));
                  const buffer = await blob.arrayBuffer();
                  AIHubBinaryDownload.postMessage(buffer);
                } catch (error) {
                  AIHubBinaryDownload.postMessage(JSON.stringify({
                    type: "error", reason: String(error)
                  }));
                }
              })();
              return "scheduled";
            })();
        """.trimIndent()

        pendingBinaryDownloads.remove(key)
        webView.evaluateJavascript(script) { result ->
            DiagnosticLogger.d(
                "DOWNLOAD",
                "blob_download_started provider=${provider.id} result=${DiagnosticLogger.scrub(result.orEmpty(), 120)} origins=${policy.messageOriginRules.joinToString("|").take(240)}"
            )
        }
    }

    private fun installBinaryDownloadBridge(
        webView: WebView,
        key: String,
        provider: ProviderSpec,
        policy: ProviderWebPolicy
    ) {
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) ||
            policy.messageOriginRules.isEmpty()
        ) return

        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                "AIHubBinaryDownload",
                policy.messageOriginRules
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || !policy.allowsMessageOrigin(sourceOrigin)) {
                    DiagnosticLogger.w(
                        "DOWNLOAD",
                        "binary_message_rejected provider=${provider.id} origin=${safeUrl(sourceOrigin.toString())}"
                    )
                    return@addWebMessageListener
                }
                when (message.type) {
                    WebMessageCompat.TYPE_STRING -> {
                        val obj = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
                        when (obj.optString("type")) {
                            "meta" -> {
                                val size = obj.optLong("size", -1L)
                                if (size < 0L || size > MAX_BINARY_DOWNLOAD_BYTES) {
                                    pendingBinaryDownloads.remove(key)
                                    return@addWebMessageListener
                                }
                                pendingBinaryDownloads[key] = BinaryDownloadMeta(
                                    fileName = sanitizeFileName(obj.optString("fileName").ifBlank { "download.bin" }),
                                    mimeType = obj.optString("mimeType").ifBlank { "application/octet-stream" },
                                    sizeBytes = size
                                )
                            }
                            "error" -> {
                                pendingBinaryDownloads.remove(key)
                                DiagnosticLogger.w(
                                    "DOWNLOAD",
                                    "blob_download_web_error provider=${provider.id} reason=${DiagnosticLogger.scrub(obj.optString("reason"), 240)}"
                                )
                                Toast.makeText(context, "Blob 下载失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    WebMessageCompat.TYPE_ARRAY_BUFFER -> {
                        val meta = pendingBinaryDownloads.remove(key) ?: return@addWebMessageListener
                        val bytes = message.arrayBuffer
                        if (bytes.size.toLong() > MAX_BINARY_DOWNLOAD_BYTES || (meta.sizeBytes >= 0 && bytes.size.toLong() != meta.sizeBytes)) {
                            DiagnosticLogger.w(
                                "DOWNLOAD",
                                "blob_download_size_mismatch provider=${provider.id} expected=${meta.sizeBytes} actual=${bytes.size}"
                            )
                            return@addWebMessageListener
                        }
                        saveBinaryDownload(bytes, meta.fileName, meta.mimeType)
                    }
                }
            }
        }.onFailure {
            DiagnosticLogger.e("DOWNLOAD", "binary_bridge_install_failed provider=${provider.id}", it)
        }
    }

    private fun saveBinaryDownload(bytes: ByteArray, fileName: String, mimeType: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("downloads-insert-failed")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("downloads-output-failed")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                val file = java.io.File(dir, fileName)
                file.outputStream().use { it.write(bytes) }
            }
            DiagnosticLogger.i(
                "DOWNLOAD",
                "binary_download_saved file=${DiagnosticLogger.scrub(fileName, 180)} bytes=${bytes.size} mime=$mimeType"
            )
            Toast.makeText(context, "下载完成：$fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            DiagnosticLogger.e("DOWNLOAD", "binary_download_save_failed file=${DiagnosticLogger.scrub(fileName, 180)}", it)
            Toast.makeText(context, "下载失败：$fileName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().ifBlank { "download.bin" }.take(180)

    private fun queryAttachmentMeta(uri: Uri, index: Int): AttachmentMeta {
        var name = ""
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return AttachmentMeta(
            name = name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "attachment-${index + 1}" },
            mimeType = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" },
            sizeBytes = size
        )
    }

    private fun cookieManagerFor(webView: WebView): CookieManager {
        if (!supportsMultiProfile) return CookieManager.getInstance()
        return runCatching { WebViewCompat.getProfile(webView).cookieManager }
            .onFailure { DiagnosticLogger.e("WEB", "profile_cookie_manager_failed", it) }
            .getOrElse { CookieManager.getInstance() }
    }

    private fun compatibleUserAgent(base: String, mode: UserAgentMode): String = when (mode) {
        UserAgentMode.DEFAULT -> base
        UserAgentMode.COMPAT_MOBILE -> base.replace("; wv", "").replace("Version/4.0 ", "")
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

    companion object {
        private const val WEB_PERMISSION_REQUEST_CODE = 4107
        private const val MAX_BINARY_DOWNLOAD_BYTES = 64 * 1024 * 1024
        private val FILE_CONFIRM_DELAYS = longArrayOf(180L, 420L, 900L, 1600L)
    }
}
