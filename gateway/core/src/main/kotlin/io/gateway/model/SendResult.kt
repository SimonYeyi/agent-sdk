package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public sealed class SendResult {
    @Serializable
    public data class Success(
        val messageId: String,
        val platform: PlatformId
    ) : SendResult()

    @Serializable
    public data class Failure(
        val error: String,
        val retryable: Boolean = false,
        val exception: String? = null
    ) : SendResult()
}
