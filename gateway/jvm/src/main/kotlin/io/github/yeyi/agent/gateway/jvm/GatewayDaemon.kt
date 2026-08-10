package io.github.yeyi.agent.gateway.jvm

import io.gateway.engine.GatewayEngineBuilder
import io.gateway.platform.feishu.FeishuAdapter
import io.gateway.platform.feishu.FeishuConfig
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
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
        println("[gateway-jvm] starting with model.name=${config.modelName}, appStorageDir=${config.appStorageDir}")

        val llmProvider = resolveLlmProvider(
            providerRaw = config.modelProvider,
            apiKey = config.modelApiKey,
            baseUrl = config.modelBaseUrl,
            model = config.modelName,
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

private const val PROVIDER_OPENAI = "openai"
private const val PROVIDER_ANTHROPIC = "anthropic"

private fun resolveLlmProvider(
    providerRaw: String,
    apiKey: String,
    baseUrl: String,
    model: String,
): LlmProvider {
    require(apiKey.isNotEmpty()) { "model.api.key is required" }
    require(baseUrl.isNotEmpty()) { "model.base.url is required" }
    require(model.isNotEmpty()) { "model.name is required" }

    val provider = when {
        providerRaw.isNotEmpty() -> {
            check(providerRaw in setOf(PROVIDER_OPENAI, PROVIDER_ANTHROPIC)) {
                "Unsupported model.provider: '$providerRaw'. " +
                        "Use '$PROVIDER_OPENAI' or '$PROVIDER_ANTHROPIC' " +
                        "(or leave empty to infer from model.base.url)."
            }
            providerRaw
        }

        baseUrl.contains(PROVIDER_ANTHROPIC, ignoreCase = true) -> PROVIDER_ANTHROPIC
        else -> PROVIDER_OPENAI
    }

    return if (provider == PROVIDER_ANTHROPIC) {
        AnthropicProvider(apiKey = apiKey, model = model, baseUrl = baseUrl)
    } else {
        OpenAiProvider(apiKey = apiKey, model = model, baseUrl = baseUrl)
    }
}
