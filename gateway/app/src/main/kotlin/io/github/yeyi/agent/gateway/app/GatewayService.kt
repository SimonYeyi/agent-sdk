package io.github.yeyi.agent.gateway.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.session.SessionManager
import io.gateway.api.GatewayEngine
import io.gateway.engine.GatewayEngineBuilder
import io.gateway.platform.feishu.FeishuAdapter
import io.gateway.platform.feishu.FeishuConfig
import io.github.yeyi.agent.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatewayService : Service() {

    private var engine: GatewayEngine? = null
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch { startEngine() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            engine?.stop()
            supervisorJob.cancel()
        }
    }

    private suspend fun startEngine() {
        val llmProvider = resolveLlmProvider(
            providerRaw = BuildConfig.MODEL_PROVIDER,
            apiKey = BuildConfig.MODEL_API_KEY,
            baseUrl = BuildConfig.MODEL_BASE_URL,
            model = BuildConfig.MODEL_NAME,
        )
        val sessionManager = SessionManager(filesDir, HookPipeline())

        val feishuConfig = FeishuConfig(
            appId = BuildConfig.FEISHU_APP_ID,
            appSecret = BuildConfig.FEISHU_APP_SECRET,
        )
        val feishuAdapter = FeishuAdapter(feishuConfig, serviceScope)

        val createAgent: suspend (String, String, String) -> Agent =
            { accountId, sessionId, sessionName ->
                val session = sessionManager.getOrCreate(accountId, sessionName, sessionId)
                agent {
                    memory(session.memory)
                    llmProvider(llmProvider)
                }
            }

        val agentRunner = DefaultAgentRunner(createAgent)
        val builtEngine = GatewayEngineBuilder()
            .withFileSessionStorage(filesDir)
            .withAgentRunner(agentRunner)
            .build()

        builtEngine.registerAdapter(feishuAdapter)
        builtEngine.start()

        engine = builtEngine
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gateway Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gateway Bot")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    companion object {
        const val START = "io.github.yeyi.agent.gateway.app.START"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "gateway_service"
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
    require(apiKey.isNotEmpty()) { "MODEL_API_KEY is required (set it in local.properties)" }
    require(baseUrl.isNotEmpty()) { "MODEL_BASE_URL is required (set it in local.properties)" }
    require(model.isNotEmpty()) { "MODEL_NAME is required (set it in local.properties)" }

    val provider = when {
        providerRaw.isNotEmpty() -> {
            check(providerRaw in setOf(PROVIDER_OPENAI, PROVIDER_ANTHROPIC)) {
                "Unsupported MODEL_PROVIDER: '$providerRaw'. " +
                        "Use '$PROVIDER_OPENAI' or '$PROVIDER_ANTHROPIC' " +
                        "(or leave empty to infer from MODEL_BASE_URL)."
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
