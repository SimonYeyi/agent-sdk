package io.gateway.engine

import io.gateway.api.GatewaySessionManager
import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap

internal class InMemoryGatewaySessionManager : GatewaySessionManager {

    private val sessions = ConcurrentHashMap<String, GatewaySession>()
    private val allGatewaySessionsFlow = MutableStateFlow<List<GatewaySession>>(emptyList())

    override suspend fun getOrCreateSession(source: MessageSource): GatewaySession {
        val key = source.sessionKey()
        return sessions.getOrPut(key) {
            GatewaySession(
                key = key,
                platform = source.platform,
                chatId = source.chatId,
                userId = source.userId,
                chatType = source.chatType,
                chatName = source.chatName,
                userName = source.userName
            ).also { updateFlow() }
        }
    }

    override suspend fun getSession(sessionKey: String): GatewaySession? =
        sessions[sessionKey]

    override suspend fun updateSession(session: GatewaySession) {
        sessions[session.key] = session
        updateFlow()
    }

    override suspend fun deleteSession(sessionKey: String) {
        sessions.remove(sessionKey)
        updateFlow()
    }

    override suspend fun getActiveSessions(): List<GatewaySession> =
        sessions.values.toList()

    override suspend fun markProcessing(sessionKey: String) {
        sessions[sessionKey]?.let {
            sessions[sessionKey] = it.copy(isProcessing = true)
            updateFlow()
        }
    }

    override suspend fun markProcessingComplete(sessionKey: String) {
        sessions[sessionKey]?.let {
            sessions[sessionKey] = it.copy(
                isProcessing = false,
                lastMessageAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            )
            updateFlow()
        }
    }

    override fun isProcessing(sessionKey: String): Boolean =
        sessions[sessionKey]?.isProcessing ?: false

    override suspend fun updateSessionStats(
        sessionKey: String,
        messageCountDelta: Int,
        turnCountDelta: Int,
        inputTokensDelta: Long,
        outputTokensDelta: Long
    ) {
        sessions[sessionKey]?.let {
            sessions[sessionKey] = it.copy(
                messageCount = it.messageCount + messageCountDelta,
                turnCount = it.turnCount + turnCountDelta,
                inputTokens = it.inputTokens + inputTokensDelta,
                outputTokens = it.outputTokens + outputTokensDelta,
                lastMessageAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            )
            updateFlow()
        }
    }

    override fun observeSession(sessionKey: String): Flow<GatewaySession?> =
        allGatewaySessionsFlow.map { list -> list.find { it.key == sessionKey } }

    override fun observeAllSessions(): Flow<List<GatewaySession>> =
        allGatewaySessionsFlow.asStateFlow()

    private fun updateFlow() {
        allGatewaySessionsFlow.value = sessions.values.toList()
    }
}
