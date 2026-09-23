package com.yagay.aihub.web

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatWindow
import com.yagay.aihub.model.ProviderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin AIHub -> YBrowser client.
 *
 * AIHub does not host WebView/Gecko or provider scripts anymore. Every provider
 * command is executed by YBrowser's AI engine through the exported bridge.
 */
class WindowWebRuntime(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val baseUri = Uri.parse(BASE_URI)
    private val windows =
        mutableMapOf<String, ChatWindow>()

    private var fileSelectionListener:
        ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)? = null
    private var pageChangeListener:
        ((String, ProviderSpec, String) -> Unit)? = null
    private var historyChangeListener:
        ((String) -> Unit)? = null

    private val observer =
        object : ContentObserver(
            Handler(Looper.getMainLooper())
        ) {
            override fun onChange(
                selfChange: Boolean,
                uri: Uri?,
            ) {
                val segments =
                    uri?.pathSegments.orEmpty()
                val windowId =
                    if (
                        segments.size == 2 &&
                        segments[0] ==
                            PATH_CONVERSATIONS
                    ) {
                        segments[1]
                    } else {
                        ""
                    }

                if (windowId.isNotBlank()) {
                    historyChangeListener
                        ?.invoke(windowId)
                } else {
                    windows.keys.toList()
                        .forEach {
                            historyChangeListener
                                ?.invoke(it)
                        }
                }
            }
        }

    init {
        resolver.registerContentObserver(
            baseUri,
            true,
            observer,
        )
    }

    fun ensureSession(window: ChatWindow) {
        windows[window.id] = window
        call(
            METHOD_ENSURE_SESSION,
            bundleFor(
                window.id,
                ProviderSpec(
                    id = window.providerId,
                    name = window.providerId,
                    shortName = window.providerId,
                    homeUrl = "",
                    scriptAsset = "",
                ),
                window,
            ),
        )
    }

    fun setHistoryChangeListener(
        listener: ((String) -> Unit)?,
    ) {
        historyChangeListener = listener
    }

    fun setFileChooserLauncher(
        launcher: ((Intent) -> Unit)?,
    ) {
        // Web file chooser belongs to YBrowser now.
    }

    fun setFileSelectionListener(
        listener: ((
            windowId: String,
            ProviderSpec,
            List<AttachmentMeta>,
        ) -> Unit)?,
    ) {
        fileSelectionListener = listener
    }

    fun setPageChangeListener(
        listener: ((
            windowId: String,
            ProviderSpec,
            String,
        ) -> Unit)?,
    ) {
        pageChangeListener = listener
    }

    fun handleFileChooserResult(
        resultCode: Int,
        data: Intent?,
    ) {
        // File selection happens in AIHub and is forwarded as granted URIs.
    }

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean = false

    fun openWeb(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        ensureSession(window)

        val intent = Intent(ACTION_OPEN_AI_WEB).apply {
            component = ComponentName(
                YBROWSER_PACKAGE,
                YBROWSER_AI_ACTIVITY,
            )
            putExtra(EXTRA_WINDOW_ID, window.id)
            putExtra(EXTRA_PROVIDER_ID, provider.id)
            (window.url)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    putExtra(EXTRA_URL, it)
                    putExtra(EXTRA_BOUND_URL, it)
                }
            putExtra(EXTRA_TITLE, window.title)
            if (context !is Activity) {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        }
        context.startActivity(intent)
    }

    fun currentUrl(
        windowId: String,
        provider: ProviderSpec,
    ): String? {
        val result = call(
            METHOD_CURRENT_URL,
            bundleFor(windowId, provider),
        )
        return result
            ?.getString(RESULT_URL)
            ?.takeIf { it.isNotBlank() }
            ?.also {
                pageChangeListener
                    ?.invoke(
                        windowId,
                        provider,
                        it,
                    )
            }
    }

    suspend fun isLoggedIn(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        currentUrl(windowId, provider)
            ?.startsWith("http") == true

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>,
    ): WebRuntime.AttachmentAttachResult =
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) {
                return@withContext
                    WebRuntime.AttachmentAttachResult(
                        0,
                        emptyList(),
                        "no-selection",
                    )
            }

            uris.forEach { uri ->
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                appContext.grantUriPermission(
                    YBROWSER_PACKAGE,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val extras =
                bundleFor(windowId, provider)
            extras.putStringArrayList(
                EXTRA_URIS,
                ArrayList(
                    uris.map(Uri::toString)
                ),
            )

            val result =
                call(
                    METHOD_ATTACH_FILES,
                    extras,
                )
            val count =
                result?.getInt(
                    RESULT_ATTACHED_COUNT,
                    0,
                ) ?: 0
            val names =
                result?.getStringArrayList(
                    RESULT_NAMES
                ).orEmpty()

            if (count > 0) {
                val metadata =
                    uris.take(count)
                        .mapIndexed {
                                index,
                                uri,
                            ->
                            queryAttachmentMeta(
                                uri,
                                index,
                            )
                        }
                fileSelectionListener
                    ?.invoke(
                        windowId,
                        provider,
                        metadata,
                    )
            }

            WebRuntime.AttachmentAttachResult(
                attachedCount = count,
                names = names,
                failure =
                    result?.getString(
                        RESULT_FAILURE
                    ),
            )
        }

    suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val extras =
                bundleFor(windowId, provider)
            extras.putString(
                EXTRA_PROMPT,
                prompt,
            )
            val result =
                call(METHOD_SEND, extras)
            val current =
                result?.getString(RESULT_URL)
            if (!current.isNullOrBlank()) {
                pageChangeListener
                    ?.invoke(
                        windowId,
                        provider,
                        current,
                    )
            }
            result?.getBoolean(
                RESULT_OK,
                false,
            ) == true
        }

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec,
    ): WebRuntime.ResponseSnapshot =
        withContext(Dispatchers.IO) {
            val result =
                call(
                    METHOD_RESPONSE_SNAPSHOT,
                    bundleFor(
                        windowId,
                        provider,
                    ),
                )
            WebRuntime.ResponseSnapshot(
                text =
                    result?.getString(
                        RESULT_TEXT
                    ).orEmpty(),
                key =
                    result?.getString(
                        RESULT_KEY
                    ).orEmpty(),
                source =
                    result?.getString(
                        RESULT_SOURCE
                    ).orEmpty()
                        .ifBlank { "none" },
                responseCount =
                    result?.getInt(
                        RESULT_RESPONSE_COUNT,
                        0,
                    ) ?: 0,
                turnCount =
                    result?.getInt(
                        RESULT_TURN_COUNT,
                        0,
                    ) ?: 0,
                state =
                    result?.getString(
                        RESULT_STATE
                    ).orEmpty()
                        .ifBlank { "idle" },
                reason =
                    result?.getString(
                        RESULT_REASON
                    ).orEmpty(),
                path =
                    result?.getString(
                        RESULT_PATH
                    ).orEmpty(),
                quietMs =
                    result?.getLong(
                        RESULT_QUIET_MS,
                        -1L,
                    ) ?: -1L,
            )
        }

    suspend fun probeSummary(
        windowId: String,
        provider: ProviderSpec,
    ): String =
        "ybrowser-bridge:" +
            (
                currentUrl(
                    windowId,
                    provider,
                ) ?: "no-url"
            )

    suspend fun stop(
        windowId: String,
        provider: ProviderSpec,
    ) {
        withContext(Dispatchers.IO) {
            call(
                METHOD_STOP,
                bundleFor(
                    windowId,
                    provider,
                ),
            )
        }
    }

    fun markAttachmentsSubmitted(
        windowId: String,
        provider: ProviderSpec,
    ) {
        call(
            METHOD_MARK_ATTACHMENTS_SUBMITTED,
            bundleFor(
                windowId,
                provider,
            ),
        )
        fileSelectionListener
            ?.invoke(
                windowId,
                provider,
                emptyList(),
            )
    }

    fun canGoBack(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean = false

    fun goBack(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean = false

    fun resetProviderSession(
        windowId: String,
        provider: ProviderSpec,
    ) {
        call(
            METHOD_RELOAD,
            bundleFor(
                windowId,
                provider,
            ),
        )
    }

    fun destroyWindow(
        windowId: String,
        provider: ProviderSpec,
    ) {
        windows.remove(windowId)
    }

    fun flushCookies() = Unit

    fun destroy() {
        runCatching {
            resolver.unregisterContentObserver(
                observer
            )
        }
        historyChangeListener = null
        fileSelectionListener = null
        pageChangeListener = null
        windows.clear()
    }

    private fun bundleFor(
        windowId: String,
        provider: ProviderSpec,
        explicit: ChatWindow? = null,
    ): Bundle {
        val window =
            explicit ?: windows[windowId]
        return Bundle().apply {
            putString(EXTRA_WINDOW_ID, windowId)
            putString(
                EXTRA_PROVIDER_ID,
                provider.id,
            )
            window?.let {
                putString(EXTRA_TITLE, it.title)
                it.url
                    ?.takeIf { value ->
                        value.isNotBlank()
                    }
                    ?.let { value ->
                        putString(
                            EXTRA_URL,
                            value,
                        )
                        putString(
                            EXTRA_BOUND_URL,
                            value,
                        )
                    }
            }
        }
    }

    private fun call(
        method: String,
        extras: Bundle,
    ): Bundle? =
        resolver.call(
            baseUri,
            method,
            null,
            extras,
        )

    private fun queryAttachmentMeta(
        uri: Uri,
        index: Int,
    ): AttachmentMeta {
        var name = ""
        var size = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )
                    val sizeIndex =
                        cursor.getColumnIndex(
                            OpenableColumns.SIZE
                        )
                    if (nameIndex >= 0) {
                        name =
                            cursor.getString(
                                nameIndex
                            ).orEmpty()
                    }
                    if (
                        sizeIndex >= 0 &&
                        !cursor.isNull(sizeIndex)
                    ) {
                        size =
                            cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return AttachmentMeta(
            name =
                name.ifBlank {
                    uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?: "attachment-${index + 1}"
                },
            mimeType =
                resolver.getType(uri)
                    .orEmpty()
                    .ifBlank {
                        "application/octet-stream"
                    },
            sizeBytes = size,
        )
    }

    companion object {
        private const val YBROWSER_PACKAGE =
            "com.yagay.YBrowser"
        private const val YBROWSER_AI_ACTIVITY =
            "com.yagay.ybrowser.ai.AiWorkspaceActivity"

        private const val BASE_URI =
            "content://com.yagay.YBrowser.ai.bridge"
        private const val PATH_CONVERSATIONS =
            "conversations"

        private const val ACTION_OPEN_AI_WEB =
            "com.yagay.YBrowser.action.OPEN_AI_WEB"
        private const val EXTRA_WINDOW_ID =
            "com.yagay.YBrowser.extra.AI_WINDOW_ID"
        private const val EXTRA_PROVIDER_ID =
            "com.yagay.YBrowser.extra.AI_PROVIDER_ID"
        private const val EXTRA_URL =
            "com.yagay.YBrowser.extra.URL"
        private const val EXTRA_BOUND_URL =
            "com.yagay.YBrowser.extra.BIND_URL"
        private const val EXTRA_TITLE =
            "title"

        private const val METHOD_ENSURE_SESSION =
            "ensure_session"
        private const val METHOD_ATTACH_FILES =
            "attach_files"
        private const val METHOD_SEND = "send"
        private const val METHOD_STOP = "stop"
        private const val METHOD_RESPONSE_SNAPSHOT =
            "response_snapshot"
        private const val METHOD_CURRENT_URL =
            "current_url"
        private const val METHOD_RELOAD = "reload"
        private const val METHOD_MARK_ATTACHMENTS_SUBMITTED =
            "mark_attachments_submitted"

        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_URIS = "uris"

        private const val RESULT_OK = "ok"
        private const val RESULT_ATTACHED_COUNT =
            "attached_count"
        private const val RESULT_NAMES = "names"
        private const val RESULT_FAILURE = "failure"
        private const val RESULT_URL = "url"
        private const val RESULT_TEXT = "text"
        private const val RESULT_KEY = "key"
        private const val RESULT_SOURCE = "source"
        private const val RESULT_RESPONSE_COUNT =
            "response_count"
        private const val RESULT_TURN_COUNT =
            "turn_count"
        private const val RESULT_STATE = "state"
        private const val RESULT_REASON = "reason"
        private const val RESULT_PATH = "path"
        private const val RESULT_QUIET_MS = "quiet_ms"
    }
}
