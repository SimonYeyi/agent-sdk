package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class PlatformError(
    val platform: PlatformId,
    val error: String,
    val code: String? = null,
    val details: String? = null
)
