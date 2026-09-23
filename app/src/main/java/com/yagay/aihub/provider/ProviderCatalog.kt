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


    fun fromUrl(url: String?): ProviderSpec? {
        if (url.isNullOrBlank()) return null
        val host = runCatching {
            android.net.Uri.parse(url).host.orEmpty().lowercase()
        }.getOrDefault("")
        return when {
            host == "chatgpt.com" || host.endsWith(".chatgpt.com") ->
                all.firstOrNull { it.id == "chatgpt" }
            host == "gemini.google.com" ->
                all.firstOrNull { it.id == "gemini" }
            host == "claude.ai" || host.endsWith(".claude.ai") ->
                all.firstOrNull { it.id == "claude" }
            host == "grok.com" || host.endsWith(".grok.com") ->
                all.firstOrNull { it.id == "grok" }
            host == "chat.deepseek.com" ->
                all.firstOrNull { it.id == "deepseek" }
            host == "chat.qwen.ai" ->
                all.firstOrNull { it.id == "qwen" }
            else -> null
        }
    }
}
