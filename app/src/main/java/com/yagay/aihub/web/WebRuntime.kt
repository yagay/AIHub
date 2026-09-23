package com.yagay.aihub.web

/**
 * UI-side bridge DTOs only.
 *
 * Provider protocol, browser state, WebView/Gecko and network parsing are
 * owned by YBrowser. AIHub keeps these small result models so its Compose UI
 * does not depend on the browser implementation.
 */
class WebRuntime {
    data class AttachmentAttachResult(
        val attachedCount: Int,
        val names: List<String>,
        val failure: String? = null,
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
        val quietMs: Long = -1L,
    ) {
        val isGenerating: Boolean
            get() =
                state == "generating" ||
                    state == "queued" ||
                    state == "uploading"
    }
}
