package io.gateway.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
public data class GatewaySession(
    val key: String,
    val platform: PlatformId,
    val chatId: String,
    val userId: String,
    val chatType: ChatType,
    val chatName: String? = null,
    val userName: String? = null,
    val createdAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    val lastMessageAt: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    val messageCount: Int = 0,
    val turnCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
    val isProcessing: Boolean = false
)
