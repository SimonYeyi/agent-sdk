package io.gateway.engine

import io.gateway.api.DeliveryRouter
import io.gateway.api.PlatformAdapter
import io.gateway.model.OutgoingContent
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

internal class DefaultDeliveryRouter : DeliveryRouter {

    private val adapters = ConcurrentHashMap<PlatformId, PlatformAdapter>()

    private val _deliveryResults = MutableSharedFlow<Pair<PlatformId, SendResult>>(
        extraBufferCapacity = 200
    )

    override fun registerAdapter(adapter: PlatformAdapter) {
        adapters[adapter.platformId] = adapter
    }

    override fun unregisterAdapter(platformId: PlatformId) {
        adapters.remove(platformId)
    }

    override fun getAdapter(platformId: PlatformId): PlatformAdapter? = adapters[platformId]

    override suspend fun deliverText(
        platform: PlatformId,
        chatId: String,
        text: String,
        replyTo: String?,
        threadId: String?
    ): SendResult {
        return deliver(
            platform = platform,
            chatId = chatId,
            content = OutgoingContent.Text(text),
            replyTo = replyTo,
            threadId = threadId
        )
    }

    override suspend fun deliver(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String?,
        threadId: String?
    ): SendResult {
        val message = OutgoingMessage(
            chatId = chatId,
            content = content,
            replyToMessageId = replyTo,
            threadId = threadId
        )
        return deliverMessage(message, platform)
    }

    override suspend fun deliverMessage(message: OutgoingMessage, platform: PlatformId): SendResult {
        val adapter = adapters[platform]
            ?: return SendResult.Failure(
                error = "No adapter found for platform: ${platform.value}",
                retryable = false
            )

        val result = adapter.sendMessage(message)
        _deliveryResults.emit(platform to result)
        return result
    }

    override fun observeDeliveryResults(): Flow<Pair<PlatformId, SendResult>> =
        _deliveryResults.asSharedFlow()
}
