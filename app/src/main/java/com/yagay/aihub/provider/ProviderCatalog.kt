package com.yagay.aihub.provider

import com.yagay.aihub.model.ProviderSpec

/**
 * Display metadata only. Provider protocol implementations live in YBrowser.
 */
object ProviderCatalog {
    val all = listOf(
        ProviderSpec(
            "chatgpt",
            "ChatGPT",
            "GPT",
            "https://chatgpt.com/",
        ),
        ProviderSpec(
            "claude",
            "Claude",
            "C",
            "https://claude.ai/new",
        ),
        ProviderSpec(
            "gemini",
            "Gemini",
            "G",
            "https://gemini.google.com/app",
        ),
        ProviderSpec(
            "grok",
            "Grok",
            "X",
            "https://grok.com/",
        ),
        ProviderSpec(
            "deepseek",
            "DeepSeek",
            "D",
            "https://chat.deepseek.com/",
        ),
        ProviderSpec(
            "qwen",
            "Qwen",
            "Q",
            "https://chat.qwen.ai/",
        ),
    )

    fun byId(id: String): ProviderSpec =
        all.firstOrNull {
            it.id == id
        } ?: all.first()
}
