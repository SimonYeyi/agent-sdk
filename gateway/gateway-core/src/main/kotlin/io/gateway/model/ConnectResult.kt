package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ConnectResult {
    @Serializable
    data class Success(val platform: PlatformId) : ConnectResult()

    @Serializable
    data class Failure(
        val error: String,
        val retryable: Boolean = false
    ) : ConnectResult()
}
