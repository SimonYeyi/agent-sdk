package io.github.yeyi.agent.gateway.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.session.SessionManager
import io.gateway.api.GatewayEngine
import io.gateway.engine.GatewayEngineBuilder
import io.gateway.platform.feishu.FeishuAdapter
import io.gateway.platform.feishu.FeishuConfig
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
        val llmProvider = AnthropicProvider(
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
            model = BuildConfig.ANTHROPIC_MODEL,
            baseUrl = BuildConfig.ANTHROPIC_BASE_URL,
        )
        val sessionManager = SessionManager(filesDir)

        val feishuConfig = FeishuConfig(
            appId = BuildConfig.FEISHU_APP_ID,
            appSecret = BuildConfig.FEISHU_APP_SECRET,
        )
        val feishuAdapter = FeishuAdapter(feishuConfig, serviceScope)

        val createAgent: suspend (String, String, String) -> io.github.yeyi.agent.Agent = { accountId, sessionId, sessionName ->
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
