package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class MessageMetadata(
    val replyToMessageId: String? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val mentions: List<Mention> = emptyList(),
    val stickerId: String? = null
)
