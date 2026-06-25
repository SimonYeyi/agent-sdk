package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class OutgoingMessage(
    val chatId: String,
    val content: OutgoingContent,
    val replyToMessageId: String? = null,
    val threadId: String? = null,
    val metadata: OutgoingMetadata = OutgoingMetadata()
)
