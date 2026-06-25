package io.gateway.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
public data class IncomingMessage(
    val id: MessageId,
    val source: MessageSource,
    val content: MessageContent,
    val metadata: MessageMetadata = MessageMetadata(),
    val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
)
