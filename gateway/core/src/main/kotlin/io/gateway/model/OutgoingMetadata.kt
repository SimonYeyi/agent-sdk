package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class OutgoingMetadata(
    val disablePreview: Boolean = false,
    val disableNotification: Boolean = false,
    val extra: Map<String, String> = emptyMap()
)
