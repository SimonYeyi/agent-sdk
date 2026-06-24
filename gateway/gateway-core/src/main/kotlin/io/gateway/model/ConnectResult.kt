package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public sealed class ConnectResult {
    @Serializable
    public data class Success(val platform: PlatformId) : ConnectResult()

    @Serializable
    public data class Failure(
        val error: String,
        val retryable: Boolean = false
    ) : ConnectResult()
}
