package io.gateway.api

import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.model.GatewayConfig
import io.gateway.model.GatewayState
import io.gateway.model.GatewayError
import kotlinx.coroutines.flow.Flow

interface GatewayEngine {

    val config: GatewayConfig

    val isRunning: Boolean

    suspend fun start()

    suspend fun stop()

    fun registerAdapter(adapter: PlatformAdapter)

    fun unregisterAdapter(platformId: PlatformId)

    fun getAdapter(platformId: PlatformId): PlatformAdapter?

    fun getAdapters(): List<PlatformAdapter>

    fun setSessionManager(manager: GatewaySessionManager)

    fun setAgentRunner(runner: AgentRunner)

    fun setHookPipeline(pipeline: HookPipeline)

    fun registerHook(hook: HookPipeline.Hook)

    suspend fun sendMessage(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    fun observeState(): Flow<GatewayState>

    fun observeErrors(): Flow<GatewayError>
}
