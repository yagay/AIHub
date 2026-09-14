package com.yagay.aihub.provider

import com.yagay.aihub.model.ProviderSpec

object ProviderCatalog {
    val all = listOf(
        ProviderSpec("chatgpt", "ChatGPT", "GPT", "https://chatgpt.com/", "providers/chatgpt.js"),
        ProviderSpec("claude", "Claude", "C", "https://claude.ai/new", "providers/claude.js"),
        ProviderSpec("gemini", "Gemini", "G", "https://gemini.google.com/app", "providers/gemini.js"),
        ProviderSpec("grok", "Grok", "X", "https://grok.com/", "providers/grok.js"),
        ProviderSpec("deepseek", "DeepSeek", "D", "https://chat.deepseek.com/", "providers/deepseek.js"),
        ProviderSpec("qwen", "Qwen", "Q", "https://chat.qwen.ai/", "providers/qwen.js")
    )

    fun byId(id: String): ProviderSpec = all.firstOrNull { it.id == id } ?: all.first()
}
