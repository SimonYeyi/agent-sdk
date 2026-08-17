package io.gateway.api

import io.gateway.model.MessageSource
import io.gateway.model.GatewaySession
import kotlinx.coroutines.flow.Flow

public interface GatewaySessionManager {

    public suspend fun getOrCreateSession(source: MessageSource): GatewaySession

    public suspend fun getSession(sessionKey: String): GatewaySession?

    public suspend fun updateSession(session: GatewaySession)

    public suspend fun deleteSession(sessionKey: String)

    public suspend fun getAllSessions(): List<GatewaySession>

    public suspend fun markProcessing(sessionKey: String)

    public suspend fun markProcessingComplete(sessionKey: String)

    /**
     * 把所有 isProcessing=true 的会话标记为处理完成,用于进程启动时恢复上次异常退出留下的脏状态。
     * 各实现自行决定如何处理自己的持久化层;调用方不假设实现细节。
     */
    public suspend fun markAllProcessingComplete()

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
