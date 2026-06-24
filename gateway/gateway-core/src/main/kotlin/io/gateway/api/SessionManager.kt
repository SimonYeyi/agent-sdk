package io.gateway.api

import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow

public interface GatewaySessionManager {

    public suspend fun getOrCreateGatewaySession(source: MessageSource): GatewaySession

    public suspend fun getGatewaySession(sessionKey: String): GatewaySession?

    public suspend fun updateGatewaySession(session: GatewaySession)

    public suspend fun deleteGatewaySession(sessionKey: String)

    public suspend fun getActiveGatewaySessions(): List<GatewaySession>

    public suspend fun markProcessing(sessionKey: String)

    public suspend fun markProcessingComplete(sessionKey: String)

    public fun isProcessing(sessionKey: String): Boolean

    public suspend fun updateGatewaySessionStats(
        sessionKey: String,
        messageCountDelta: Int = 0,
        turnCountDelta: Int = 0,
        inputTokensDelta: Long = 0,
        outputTokensDelta: Long = 0
    )

    public fun observeGatewaySession(sessionKey: String): Flow<GatewaySession?>

    public fun observeAllGatewaySessions(): Flow<List<GatewaySession>>
}
