package com.yagay.aihub.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatWindow
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.SessionKey

class WindowWebRuntime(context: Context) {
    private val runtime = WebRuntime(context, useProfiles = false)

    private fun session(windowId: String, providerId: String) =
        SessionKey(providerId = providerId, accountId = windowId)

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) =
        runtime.setFileChooserLauncher(launcher)

    fun setFileSelectionListener(
        listener: ((windowId: String, ProviderSpec, List<AttachmentMeta>) -> Unit)?
    ) {
        runtime.setFileSelectionListener(
            listener?.let { target ->
                { session, provider, attachments ->
                    target(session.accountId, provider, attachments)
                }
            }
        )
    }

    fun setPageChangeListener(
        listener: ((windowId: String, ProviderSpec, String) -> Unit)?
    ) {
        runtime.setPageChangeListener(
            listener?.let { target ->
                { session, provider, url ->
                    target(session.accountId, provider, url)
                }
            }
        )
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) =
        runtime.handleFileChooserResult(resultCode, data)

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean = runtime.handleAndroidPermissionResult(requestCode, permissions, grantResults)

    fun attach(host: FrameLayout, window: ChatWindow, provider: ProviderSpec) {
        runtime.attach(
            host = host,
            session = session(window.id, provider.id),
            provider = provider,
            preferredUrl = window.url
        )
    }

    fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        runtime.currentUrl(session(windowId, provider.id), provider)

    suspend fun isLoggedIn(windowId: String, provider: ProviderSpec): Boolean =
        runtime.isLoggedIn(session(windowId, provider.id), provider)

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>
    ): WebRuntime.AttachmentAttachResult =
        runtime.attachFiles(session(windowId, provider.id), provider, uris)

    suspend fun send(windowId: String, provider: ProviderSpec, prompt: String): Boolean =
        runtime.send(session(windowId, provider.id), provider, prompt)

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec
    ): WebRuntime.ResponseSnapshot =
        runtime.responseSnapshot(session(windowId, provider.id), provider)

    suspend fun probeSummary(windowId: String, provider: ProviderSpec): String =
        runtime.probeSummary(session(windowId, provider.id), provider)

    suspend fun capabilities(windowId: String, provider: ProviderSpec): ProviderCapabilities =
        runtime.capabilities(session(windowId, provider.id), provider)

    suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        value: String? = null
    ): String = runtime.performAction(session(windowId, provider.id), provider, action, value)

    suspend fun stop(windowId: String, provider: ProviderSpec) =
        runtime.stop(session(windowId, provider.id), provider)

    fun markAttachmentsSubmitted(windowId: String, provider: ProviderSpec) =
        runtime.markAttachmentsSubmitted(session(windowId, provider.id), provider)

    fun canGoBack(windowId: String, provider: ProviderSpec): Boolean =
        runtime.canGoBack(session(windowId, provider.id), provider)

    fun goBack(windowId: String, provider: ProviderSpec): Boolean =
        runtime.goBack(session(windowId, provider.id), provider)

    fun resetProviderSession(windowId: String, provider: ProviderSpec) =
        runtime.resetSession(session(windowId, provider.id), provider)

    fun destroyWindow(windowId: String, provider: ProviderSpec) =
        runtime.destroySession(session(windowId, provider.id), provider)

    fun flushCookies() = runtime.flushCookies()

    fun destroy() = runtime.destroy()
}
