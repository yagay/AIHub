package com.yagay.aihub.model

import java.util.UUID

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ProviderSpec(
    val id: String,
    val name: String,
    val shortName: String,
    val homeUrl: String,
    val scriptAsset: String
)

data class AccountProfile(
    val id: String,
    val providerId: String,
    val label: String
)

data class SessionKey(
    val providerId: String,
    val accountId: String
) {
    val storageKey: String get() = "${providerId}_${accountId}"
    val webProfileName: String
        get() = "aihub_${providerId}_${accountId}"
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
}
