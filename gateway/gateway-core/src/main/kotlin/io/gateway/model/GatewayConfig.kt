package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class GatewayConfig(
    val maxConcurrentSessions: Int = 10,
    val maxInterruptDepth: Int = 3,
    val enableTypingIndicator: Boolean = true,
    val enableStreamOutput: Boolean = false,
    val messageRetryCount: Int = 2,
    val messageRetryDelayMs: Long = 2000,
    val sessionResetPolicy: SessionResetPolicy = SessionResetPolicy.NONE
)

@Serializable
public enum class SessionResetPolicy {
    NONE,
    DAILY,
    IDLE_HOURS,
    BOTH
}
