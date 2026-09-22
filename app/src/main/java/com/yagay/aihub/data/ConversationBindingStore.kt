package com.yagay.aihub.data

import android.content.Context
import android.net.Uri
import com.yagay.aihub.model.WindowSessionKey

class ConversationBindingStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_conversation_bindings", Context.MODE_PRIVATE)

    fun loadUrl(session: WindowSessionKey): String? =
        prefs.getString(urlKey(session), null)?.takeIf { it.isNotBlank() }

    fun saveUrl(session: WindowSessionKey, url: String) {
        val normalized = normalize(url) ?: return
        val uri = Uri.parse(normalized)
        if (!isConversationPath(session.providerId, uri.path.orEmpty())) return
        prefs.edit().putString(urlKey(session), normalized).apply()
    }

    fun clear(session: WindowSessionKey) {
        prefs.edit().remove(urlKey(session)).apply()
    }

    private fun urlKey(session: WindowSessionKey): String = "${session.storageKey}_url"

    private fun normalize(raw: String): String? = runCatching {
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" && uri.scheme != "http") return@runCatching null
        if (uri.host.isNullOrBlank()) return@runCatching null
        uri.buildUpon().clearQuery().fragment(null).build().toString()
    }.getOrNull()

    private fun isConversationPath(providerId: String, path: String): Boolean = when (providerId) {
        "chatgpt" -> Regex("^/(?:c|uc)/[^/]+/?$").containsMatchIn(path)
        "gemini" -> Regex("^/app/[^/]+/?$").containsMatchIn(path)
        "deepseek" -> Regex("^/a/chat/s/[^/]+/?$").containsMatchIn(path)
        "claude" -> Regex("/(?:chat|chats)/[^/]+").containsMatchIn(path)
        "grok" -> Regex("/(?:c|chat)/[^/]+").containsMatchIn(path)
        "qwen" -> Regex("/(?:c|chat|conversation)/[^/]+").containsMatchIn(path)
        else -> false
    }
}
