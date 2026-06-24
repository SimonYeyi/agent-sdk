package io.gateway.api

import io.gateway.model.ConnectionState
import io.gateway.model.ConnectResult
import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.gateway.model.SendResult

interface PlatformAdapter {

    val platformId: PlatformId

    val name: String

    val connectionState: ConnectionState

    suspend fun connect(): ConnectResult

    suspend fun disconnect()

    suspend fun sendMessage(message: OutgoingMessage): SendResult

    suspend fun sendTypingIndicator(chatId: String)

    suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult

    suspend fun deleteMessage(chatId: String, messageId: String): Boolean

    fun onMessageReceived(handler: (IncomingMessage) -> Unit)

    fun onConnectionStateChanged(handler: (ConnectionState) -> Unit)

    fun onError(handler: (PlatformError) -> Unit)
}
