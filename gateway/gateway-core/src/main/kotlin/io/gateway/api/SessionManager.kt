package io.gateway.api

import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow

interface GatewaySessionManager {

    suspend fun getOrCreateGatewaySession(source: MessageSource): GatewaySession

    suspend fun getGatewaySession(sessionKey: String): GatewaySession?

    suspend fun updateGatewaySession(session: GatewaySession)

    suspend fun deleteGatewaySession(sessionKey: String)

    suspend fun getActiveGatewaySessions(): List<GatewaySession>

    suspend fun markProcessing(sessionKey: String)

    suspend fun markProcessingComplete(sessionKey: String)

    fun isProcessing(sessionKey: String): Boolean

    suspend fun updateGatewaySessionStats(
        sessionKey: String,
        messageCountDelta: Int = 0,
        turnCountDelta: Int = 0,
        inputTokensDelta: Long = 0,
        outputTokensDelta: Long = 0
    )

    fun observeGatewaySession(sessionKey: String): Flow<GatewaySession?>

    fun observeAllGatewaySessions(): Flow<List<GatewaySession>>
}
