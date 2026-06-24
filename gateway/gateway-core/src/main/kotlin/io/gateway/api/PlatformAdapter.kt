package io.gateway.api

import io.gateway.model.ConnectionState
import io.gateway.model.ConnectResult
import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.gateway.model.SendResult

public interface PlatformAdapter {

    public val platformId: PlatformId

    public val name: String

    public val connectionState: ConnectionState

    public suspend fun connect(): ConnectResult

    public suspend fun disconnect()

    public suspend fun sendMessage(message: OutgoingMessage): SendResult

    public suspend fun sendTypingIndicator(chatId: String)

    public suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult

    public suspend fun deleteMessage(chatId: String, messageId: String): Boolean

    public fun onMessageReceived(handler: (IncomingMessage) -> Unit)

    public fun onConnectionStateChanged(handler: (ConnectionState) -> Unit)

    public fun onError(handler: (PlatformError) -> Unit)
}
