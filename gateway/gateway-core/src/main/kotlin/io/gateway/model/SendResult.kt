package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
sealed class SendResult {
    @Serializable
    data class Success(
        val messageId: String,
        val platform: PlatformId
    ) : SendResult()

    @Serializable
    data class Failure(
        val error: String,
        val retryable: Boolean = false,
        val exception: String? = null
    ) : SendResult()
}
