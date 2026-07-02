package io.github.yeyi.agent.gateway.jvm

import io.gateway.engine.GatewayEngineBuilder
import io.gateway.platform.feishu.FeishuAdapter
import io.gateway.platform.feishu.FeishuConfig
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.hook.HookPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GatewayDaemon(private val config: GatewayDaemonConfig) {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var engine: io.gateway.api.GatewayEngine? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("GatewayDaemon already started")
        }
        println("[gateway-jvm] starting with anthropic.model=${config.anthropicModel}, appStorageDir=${config.appStorageDir}")

        val llmProvider = AnthropicProvider(
            apiKey = config.anthropicApiKey,
            model = config.anthropicModel,
            baseUrl = config.anthropicBaseUrl,
        )

        val baseDir = File(config.appStorageDir).also { it.mkdirs() }
        val sessionManager = SessionManager(baseDir, HookPipeline())

        val feishuAdapter = FeishuAdapter(
            config = FeishuConfig(
                appId = config.feishuAppId,
                appSecret = config.feishuAppSecret,
            ),
            coroutineScope = scope,
        )

        val createAgent: suspend (String, String, String) -> io.github.yeyi.agent.Agent =
            { accountId, sessionId, sessionName ->
                val session = sessionManager.getOrCreate(accountId, sessionName, sessionId)
                agent {
                    memory(session.memory)
                    llmProvider(llmProvider)
                }
            }

        val builtEngine = GatewayEngineBuilder()
            .withFileSessionStorage(baseDir)
            .withAgentRunner(DefaultAgentRunner(createAgent))
            .build()

        builtEngine.registerAdapter(feishuAdapter)
        engine = builtEngine

        runBlocking {
            scope.launch { builtEngine.start() }.join()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        println("[gateway-jvm] stopping")
        runBlocking {
            scope.launch {
                runCatching { engine?.stop() }
            }.join()
        }
        scope.cancel()
    }
}
