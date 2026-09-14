package com.yagay.aihub.data

import android.content.Context
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.model.SessionKey
import org.json.JSONArray
import org.json.JSONObject

class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_conversations", Context.MODE_PRIVATE)

    fun load(session: SessionKey): List<ChatMessage> = runCatching {
        val array = JSONArray(prefs.getString(session.storageKey, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(ChatMessage(o.getString("id"), MessageRole.valueOf(o.getString("role")), o.getString("text"), o.getLong("timestamp")))
            }
        }
    }.getOrDefault(emptyList())

    fun save(session: SessionKey, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(200).forEach { message ->
            array.put(JSONObject().put("id", message.id).put("role", message.role.name).put("text", message.text).put("timestamp", message.timestamp))
        }
        prefs.edit().putString(session.storageKey, array.toString()).apply()
    }

    fun clear(session: SessionKey) {
        prefs.edit().remove(session.storageKey).apply()
    }
}
