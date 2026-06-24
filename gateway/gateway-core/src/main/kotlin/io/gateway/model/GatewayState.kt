package io.gateway.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
data class GatewayState(
    val isRunning: Boolean = false,
    val connectedPlatforms: Set<PlatformId> = emptySet(),
    val activeSessions: Int = 0,
    val processingSessions: Int = 0,
    val totalMessages: Long = 0,
    val totalErrors: Long = 0,
    val startedAt: Instant? = null
)
