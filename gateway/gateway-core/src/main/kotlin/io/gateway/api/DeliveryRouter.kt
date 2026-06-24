package io.gateway.api

import io.gateway.model.OutgoingContent
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import kotlinx.coroutines.flow.Flow

interface DeliveryRouter {

    fun registerAdapter(adapter: PlatformAdapter)

    fun unregisterAdapter(platformId: PlatformId)

    fun getAdapter(platformId: PlatformId): PlatformAdapter?

    suspend fun deliverText(
        platform: PlatformId,
        chatId: String,
        text: String,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    suspend fun deliver(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    suspend fun deliverMessage(message: OutgoingMessage, platform: PlatformId): SendResult

    fun observeDeliveryResults(): Flow<Pair<PlatformId, SendResult>>
}
