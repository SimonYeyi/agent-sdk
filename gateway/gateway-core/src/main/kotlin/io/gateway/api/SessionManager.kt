package io.gateway.api

import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow

public interface GatewaySessionManager {

    public suspend fun getOrCreateSession(source: MessageSource): GatewaySession

    public suspend fun getSession(sessionKey: String): GatewaySession?

    public suspend fun updateSession(session: GatewaySession)

    public suspend fun deleteSession(sessionKey: String)

    public suspend fun getActiveSessions(): List<GatewaySession>

    public suspend fun markProcessing(sessionKey: String)

    public suspend fun markProcessingComplete(sessionKey: String)

    public fun isProcessing(sessionKey: String): Boolean

    public suspend fun updateSessionStats(
        sessionKey: String,
        messageCountDelta: Int = 0,
        turnCountDelta: Int = 0,
        inputTokensDelta: Long = 0,
        outputTokensDelta: Long = 0
    )

    public fun observeSession(sessionKey: String): Flow<GatewaySession?>

    public fun observeAllSessions(): Flow<List<GatewaySession>>
}
