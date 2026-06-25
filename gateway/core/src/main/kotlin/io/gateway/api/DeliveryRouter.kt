package io.gateway.api

import io.gateway.model.OutgoingContent
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import kotlinx.coroutines.flow.Flow

public interface DeliveryRouter {

    public fun registerAdapter(adapter: PlatformAdapter)

    public fun unregisterAdapter(platformId: PlatformId)

    public fun getAdapter(platformId: PlatformId): PlatformAdapter?

    public suspend fun deliverText(
        platform: PlatformId,
        chatId: String,
        text: String,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    public suspend fun deliver(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    public suspend fun deliverMessage(message: OutgoingMessage, platform: PlatformId): SendResult

    public fun observeDeliveryResults(): Flow<Pair<PlatformId, SendResult>>
}
