package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class OutgoingMessage(
    val chatId: String,
    val content: OutgoingContent,
    val replyToMessageId: String? = null,
    val threadId: String? = null,
    val metadata: OutgoingMetadata = OutgoingMetadata()
)
