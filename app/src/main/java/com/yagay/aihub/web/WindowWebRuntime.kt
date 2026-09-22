package com.yagay.aihub.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatWindow
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.WindowSessionKey

class WindowWebRuntime(context: Context) {
    private val webRuntime = WebRuntime(context, useProfiles = false)
    private val geckoRuntime = GeckoProviderRuntime(context)

    private fun session(windowId: String, providerId: String) =
        WindowSessionKey(providerId = providerId, windowId = windowId)

    private fun usesGecko(provider: ProviderSpec): Boolean =
        provider.id == "gemini"

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        webRuntime.setFileChooserLauncher(launcher)
        geckoRuntime.setFileChooserLauncher(launcher)
    }

    fun setFileSelectionListener(
        listener: ((windowId: String, ProviderSpec, List<AttachmentMeta>) -> Unit)?
    ) {
        webRuntime.setFileSelectionListener(
            listener?.let { target ->
                { session, provider, attachments ->
                    target(session.windowId, provider, attachments)
                }
            }
        )
        geckoRuntime.setFileSelectionListener(listener)
    }

    fun setPageChangeListener(
        listener: ((windowId: String, ProviderSpec, String) -> Unit)?
    ) {
        webRuntime.setPageChangeListener(
            listener?.let { target ->
                { session, provider, url ->
                    target(session.windowId, provider, url)
                }
            }
        )
        geckoRuntime.setPageChangeListener(listener)
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        webRuntime.handleFileChooserResult(resultCode, data)
        geckoRuntime.handleFileChooserResult(resultCode, data)
    }

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean =
        webRuntime.handleAndroidPermissionResult(
            requestCode,
            permissions,
            grantResults
        )

    fun attach(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec
    ) {
        if (usesGecko(provider)) {
            geckoRuntime.attach(host, window, provider)
        } else {
            webRuntime.attach(
                host = host,
                session = session(window.id, provider.id),
                provider = provider,
                preferredUrl = window.url
            )
        }
    }

    fun currentUrl(
        windowId: String,
        provider: ProviderSpec
    ): String? =
        if (usesGecko(provider)) {
            geckoRuntime.currentUrl(windowId, provider)
        } else {
            webRuntime.currentUrl(
                session(windowId, provider.id),
                provider
            )
        }

    suspend fun isLoggedIn(
        windowId: String,
        provider: ProviderSpec
    ): Boolean =
        if (usesGecko(provider)) {
            geckoRuntime.isLoggedIn(windowId, provider)
        } else {
            webRuntime.isLoggedIn(
                session(windowId, provider.id),
                provider
            )
        }

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>
    ): WebRuntime.AttachmentAttachResult =
        if (usesGecko(provider)) {
            geckoRuntime.attachFiles(windowId, provider, uris)
        } else {
            webRuntime.attachFiles(
                session(windowId, provider.id),
                provider,
                uris
            )
        }

    suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String
    ): Boolean =
        if (usesGecko(provider)) {
            geckoRuntime.send(windowId, provider, prompt)
        } else {
            webRuntime.send(
                session(windowId, provider.id),
                provider,
                prompt
            )
        }

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec
    ): WebRuntime.ResponseSnapshot =
        if (usesGecko(provider)) {
            geckoRuntime.responseSnapshot(windowId, provider)
        } else {
            webRuntime.responseSnapshot(
                session(windowId, provider.id),
                provider
            )
        }

    suspend fun probeSummary(
        windowId: String,
        provider: ProviderSpec
    ): String =
        if (usesGecko(provider)) {
            geckoRuntime.probeSummary(windowId, provider)
        } else {
            webRuntime.probeSummary(
                session(windowId, provider.id),
                provider
            )
        }

    suspend fun capabilities(
        windowId: String,
        provider: ProviderSpec
    ): ProviderCapabilities =
        if (usesGecko(provider)) {
            geckoRuntime.capabilities(windowId, provider)
        } else {
            webRuntime.capabilities(
                session(windowId, provider.id),
                provider
            )
        }

    suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        value: String? = null
    ): String =
        if (usesGecko(provider)) {
            geckoRuntime.performAction(
                windowId,
                provider,
                action,
                value
            )
        } else {
            webRuntime.performAction(
                session(windowId, provider.id),
                provider,
                action,
                value
            )
        }

    suspend fun stop(
        windowId: String,
        provider: ProviderSpec
    ) {
        if (usesGecko(provider)) {
            geckoRuntime.stop(windowId, provider)
        } else {
            webRuntime.stop(
                session(windowId, provider.id),
                provider
            )
        }
    }

    fun markAttachmentsSubmitted(
        windowId: String,
        provider: ProviderSpec
    ) {
        if (usesGecko(provider)) {
            geckoRuntime.markAttachmentsSubmitted(windowId, provider)
        } else {
            webRuntime.markAttachmentsSubmitted(
                session(windowId, provider.id),
                provider
            )
        }
    }

    fun canGoBack(
        windowId: String,
        provider: ProviderSpec
    ): Boolean =
        if (usesGecko(provider)) {
            geckoRuntime.canGoBack(windowId, provider)
        } else {
            webRuntime.canGoBack(
                session(windowId, provider.id),
                provider
            )
        }

    fun goBack(
        windowId: String,
        provider: ProviderSpec
    ): Boolean =
        if (usesGecko(provider)) {
            geckoRuntime.goBack(windowId, provider)
        } else {
            webRuntime.goBack(
                session(windowId, provider.id),
                provider
            )
        }

    fun resetProviderSession(
        windowId: String,
        provider: ProviderSpec
    ) {
        if (usesGecko(provider)) {
            geckoRuntime.resetProviderSession(windowId, provider)
        } else {
            webRuntime.resetSession(
                session(windowId, provider.id),
                provider
            )
        }
    }

    fun destroyWindow(
        windowId: String,
        provider: ProviderSpec
    ) {
        if (usesGecko(provider)) {
            geckoRuntime.destroyWindow(windowId, provider)
        } else {
            webRuntime.destroySession(
                session(windowId, provider.id),
                provider
            )
        }
    }

    fun flushCookies() {
        webRuntime.flushCookies()
        geckoRuntime.flushCookies()
    }

    fun destroy() {
        webRuntime.destroy()
        geckoRuntime.destroy()
    }
}
