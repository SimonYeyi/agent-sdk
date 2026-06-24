package io.gateway.api

import io.gateway.model.OutgoingContent
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.model.GatewayConfig
import io.gateway.model.GatewayState
import io.gateway.model.GatewayError
import kotlinx.coroutines.flow.Flow

public interface GatewayEngine {

    public val config: GatewayConfig

    public val isRunning: Boolean

    public suspend fun start()

    public suspend fun stop()

    public fun registerAdapter(adapter: PlatformAdapter)

    public fun unregisterAdapter(platformId: PlatformId)

    public fun getAdapter(platformId: PlatformId): PlatformAdapter?

    public fun getAdapters(): List<PlatformAdapter>

    public fun setSessionManager(manager: GatewaySessionManager)

    public fun setAgentRunner(runner: AgentRunner)

    public fun setHookPipeline(pipeline: HookPipeline)

    public fun registerHook(hook: HookPipeline.Hook)

    public suspend fun sendMessage(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String? = null,
        threadId: String? = null
    ): SendResult

    public fun observeState(): Flow<GatewayState>

    public fun observeErrors(): Flow<GatewayError>
}
