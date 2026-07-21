package io.github.yeyi.agent.realtime

data class BridgeConfig(
    val reconnectMaxAttempts: Int = 3,
    val reconnectBackoffMs: (attempt: Int) -> Int = { attempt -> 1000 shl (attempt - 1) },
    val bossResultTimeoutMs: Long = 60_000L,
    val audioGateResetTimeoutMs: Long = 5_000L,
)
