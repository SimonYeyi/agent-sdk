package io.gateway.engine

import io.gateway.api.GatewaySessionManager
import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class FileGatewaySessionManager(baseDir: File) : GatewaySessionManager {

    private val sessionsDir: File = File(baseDir, "gateway/sessions").apply { mkdirs() }

    private val sessionCache = ConcurrentHashMap<String, GatewaySession>()
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val allGatewaySessionsFlow = MutableStateFlow<List<GatewaySession>>(emptyList())

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        loadAllGatewaySessions()
    }

    private fun sessionFile(sessionKey: String): File =
        File(sessionsDir, "$sessionKey/session.jsonl")

    private fun sessionDir(sessionKey: String): File =
        File(sessionsDir, sessionKey)

    private fun getLock(sessionKey: String): ReentrantLock =
        sessionLocks.getOrPut(sessionKey) { ReentrantLock() }

    private fun loadAllGatewaySessions() {
        if (!sessionsDir.exists()) return

        sessionsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val sessionFile = File(dir, "session.jsonl")
                if (sessionFile.exists()) {
                    runCatching {
                        val lastLine = sessionFile.readLines().lastOrNull()
                        if (!lastLine.isNullOrBlank()) {
                            val dto = json.decodeFromString<GatewaySessionDto>(lastLine)
                            val session = dto.toGatewaySession()
                            sessionCache[session.key] = session
                        }
                    }
                }
            }
        }
        allGatewaySessionsFlow.value = sessionCache.values.toList()
    }

    @Serializable
    private data class GatewaySessionDto(
        val key: String,
        val platform: String,
        val chatId: String,
        val userId: String,
        val chatType: String,
        val chatName: String? = null,
        val userName: String? = null,
        val createdAt: Long,
        val lastMessageAt: Long,
        val messageCount: Int = 0,
        val turnCount: Int = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val metadata: Map<String, String> = emptyMap(),
        val isProcessing: Boolean = false
    ) {
        fun toGatewaySession(): GatewaySession = GatewaySession(
            key = key,
            platform = io.gateway.model.PlatformId(platform),
            chatId = chatId,
            userId = userId,
            chatType = io.gateway.model.ChatType.valueOf(chatType),
            chatName = chatName,
            userName = userName,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            lastMessageAt = Instant.fromEpochMilliseconds(lastMessageAt),
            messageCount = messageCount,
            turnCount = turnCount,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            metadata = metadata,
            isProcessing = isProcessing
        )

        companion object {
            fun fromGatewaySession(session: GatewaySession): GatewaySessionDto = GatewaySessionDto(
                key = session.key,
                platform = session.platform.value,
                chatId = session.chatId,
                userId = session.userId,
                chatType = session.chatType.name,
                chatName = session.chatName,
                userName = session.userName,
                createdAt = session.createdAt.toEpochMilliseconds(),
                lastMessageAt = session.lastMessageAt.toEpochMilliseconds(),
                messageCount = session.messageCount,
                turnCount = session.turnCount,
                inputTokens = session.inputTokens,
                outputTokens = session.outputTokens,
                metadata = session.metadata,
                isProcessing = session.isProcessing
            )
        }
    }

    private fun persistGatewaySession(session: GatewaySession) {
        val dir = sessionDir(session.key)
        dir.mkdirs()

        val file = sessionFile(session.key)
        val dto = GatewaySessionDto.fromGatewaySession(session)
        val line = json.encodeToString(dto)

        getLock(session.key).withLock {
            file.appendText("$line\n")
        }
    }

    private fun updateCacheAndFlow(session: GatewaySession) {
        sessionCache[session.key] = session
        allGatewaySessionsFlow.value = sessionCache.values.toList()
    }

    override suspend fun getOrCreateSession(source: MessageSource): GatewaySession {
        val key = source.sessionKey()
        getLock(key).withLock {
            val existing = sessionCache[key]
            if (existing != null) {
                return existing
            }

            val newGatewaySession = GatewaySession(
                key = key,
                platform = source.platform,
                chatId = source.chatId,
                userId = source.userId,
                chatType = source.chatType,
                chatName = source.chatName,
                userName = source.userName
            )

            persistGatewaySession(newGatewaySession)
            updateCacheAndFlow(newGatewaySession)

            return newGatewaySession
        }
    }

    override suspend fun getSession(sessionKey: String): GatewaySession? =
        sessionCache[sessionKey]

    override suspend fun updateSession(session: GatewaySession) {
        getLock(session.key).withLock {
            persistGatewaySession(session)
            updateCacheAndFlow(session)
        }
    }

    override suspend fun deleteSession(sessionKey: String) {
        getLock(sessionKey).withLock {
            sessionCache.remove(sessionKey)
            sessionDir(sessionKey).deleteRecursively()
            allGatewaySessionsFlow.value = sessionCache.values.toList()
        }
    }

    override suspend fun getActiveSessions(): List<GatewaySession> =
        sessionCache.values.toList()

    override suspend fun markProcessing(sessionKey: String) {
        val session = sessionCache[sessionKey] ?: return
        val updated = session.copy(isProcessing = true)
        getLock(sessionKey).withLock {
            persistGatewaySession(updated)
            updateCacheAndFlow(updated)
        }
    }

    override suspend fun markProcessingComplete(sessionKey: String) {
        val session = sessionCache[sessionKey] ?: return
        val updated = session.copy(
            isProcessing = false,
            lastMessageAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
        getLock(sessionKey).withLock {
            persistGatewaySession(updated)
            updateCacheAndFlow(updated)
        }
    }

    override fun isProcessing(sessionKey: String): Boolean =
        sessionCache[sessionKey]?.isProcessing ?: false

    override suspend fun updateSessionStats(
        sessionKey: String,
        messageCountDelta: Int,
        turnCountDelta: Int,
        inputTokensDelta: Long,
        outputTokensDelta: Long
    ) {
        val session = sessionCache[sessionKey] ?: return
        val updated = session.copy(
            messageCount = session.messageCount + messageCountDelta,
            turnCount = session.turnCount + turnCountDelta,
            inputTokens = session.inputTokens + inputTokensDelta,
            outputTokens = session.outputTokens + outputTokensDelta,
            lastMessageAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
        getLock(sessionKey).withLock {
            persistGatewaySession(updated)
            updateCacheAndFlow(updated)
        }
    }

    override fun observeSession(sessionKey: String): Flow<GatewaySession?> =
        allGatewaySessionsFlow.map { sessions ->
            sessions.find { it.key == sessionKey }
        }

    override fun observeAllSessions(): Flow<List<GatewaySession>> =
        allGatewaySessionsFlow.asStateFlow()
}
