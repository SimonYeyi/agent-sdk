package io.github.yeyi.agent.demo.realtime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yeyi.agent.demo.realtime.s2s.CockpitS2sScreen
import io.github.yeyi.agent.demo.realtime.vm.RealtimeViewModel
import io.github.yeyi.agent.session.SessionManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    // 会话持久化根目录：filesDir/agent/sessions/{accountId}/{sessionId}/
                    val sessionManager = remember { SessionManager(context.filesDir) }

                    val viewModel: RealtimeViewModel = viewModel(
                        factory = RealtimeViewModel.Factory(sessionManager)
                    )
                    val boss by viewModel.boss.collectAsState()
                    val session by viewModel.session.collectAsState()
                    if (boss != null && session != null) {
                        CockpitS2sScreen(
                            apiKey = BuildConfig.VOLC_API_KEY,
                            boss = boss!!,
                            // session.id 即 S2S 协议侧 dialog id：重启后携带同一 id 接续历史对话
                            sessionId = session!!.id,
                            onBack = { finish() },
                        )
                    }
                }
            }
        }
    }
}
