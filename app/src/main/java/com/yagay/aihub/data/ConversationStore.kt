package com.yagay.aihub.data

import android.content.Context
import android.net.Uri
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.model.WindowSessionKey
import org.json.JSONArray

/**
 * Read-only UI projection of YBrowser's conversation store.
 *
 * AIHub no longer owns persistent conversation data. save()/clear() are
 * intentionally no-ops; provider/browser history is authoritative in
 * YBrowser.
 */
class ConversationStore(context: Context) {
    private val resolver =
        context.applicationContext.contentResolver

    fun load(
        session: WindowSessionKey,
    ): List<ChatMessage> =
        runCatching {
            val uri = Uri.parse(
                "$BASE_URI/$PATH_CONVERSATIONS/" +
                    Uri.encode(session.windowId)
            )

            resolver.query(
                uri,
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex =
                    cursor.getColumnIndexOrThrow("id")
                val roleIndex =
                    cursor.getColumnIndexOrThrow("role")
                val textIndex =
                    cursor.getColumnIndexOrThrow("text")
                val timestampIndex =
                    cursor.getColumnIndexOrThrow(
                        "timestamp"
                    )
                val attachmentsIndex =
                    cursor.getColumnIndexOrThrow(
                        "attachments"
                    )

                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ChatMessage(
                                id =
                                    cursor.getString(
                                        idIndex
                                    ),
                                role =
                                    runCatching {
                                        MessageRole.valueOf(
                                            cursor.getString(
                                                roleIndex
                                            )
                                        )
                                    }.getOrDefault(
                                        MessageRole.SYSTEM
                                    ),
                                text =
                                    cursor.getString(
                                        textIndex
                                    ),
                                timestamp =
                                    cursor.getLong(
                                        timestampIndex
                                    ),
                                attachments =
                                    decodeAttachments(
                                        cursor.getString(
                                            attachmentsIndex
                                        )
                                    ),
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())

    fun save(
        session: WindowSessionKey,
        messages: List<ChatMessage>,
    ) {
        // YBrowser owns persistence. UI-only optimistic state is kept in
        // WorkspaceViewModel until the bridge publishes the authoritative
        // history update.
    }

    fun clear(session: WindowSessionKey) {
        // Closing an AIHub UI tab does not delete YBrowser/provider history.
    }

    private fun decodeAttachments(
        raw: String,
    ): List<AttachmentMeta> =
        runCatching {
            val array =
                JSONArray(
                    raw.ifBlank { "[]" }
                )
            buildList {
                for (
                    index in
                    0 until array.length()
                ) {
                    val item =
                        array.optJSONObject(index)
                            ?: continue
                    add(
                        AttachmentMeta(
                            id =
                                item.optString("id")
                                    .ifBlank {
                                        "bridge-$index"
                                    },
                            name =
                                item.optString("name")
                                    .ifBlank {
                                        "attachment-" +
                                            (index + 1)
                                    },
                            mimeType =
                                item.optString(
                                    "mimeType",
                                    "application/octet-stream",
                                ),
                            sizeBytes =
                                item.optLong(
                                    "sizeBytes",
                                    0L,
                                ),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

    companion object {
        private const val BASE_URI =
            "content://com.yagay.YBrowser.ai.bridge"
        private const val PATH_CONVERSATIONS =
            "conversations"
    }
}
