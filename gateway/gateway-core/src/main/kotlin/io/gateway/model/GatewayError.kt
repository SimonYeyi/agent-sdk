package io.gateway.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
public data class GatewayError(
    val platform: PlatformId? = null,
    val sessionKey: String? = null,
    val error: String,
    val exceptionClass: String? = null,
    val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
)
