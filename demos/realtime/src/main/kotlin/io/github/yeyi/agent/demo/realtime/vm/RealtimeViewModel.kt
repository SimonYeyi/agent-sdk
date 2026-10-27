package io.github.yeyi.agent.demo.realtime.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.demo.realtime.smartCockpit.CockpitAgent
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台 Boss 宿主：固定会话 getOrCreate + 配置驱动 spawn。
 *
 * Realtime(S2S) demo 以 BossAgent 为前台 Agent（语音委托任务的执行方），
 * 与文本聊天 demo 共享同一套智能座舱场景；会话记忆持久化，重启可续聊。
 */
class RealtimeViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private companion object {
        /** 固定账号/会话标识：应用重启后通过 getOrCreate 复用同一会话，Boss 记忆持久化续聊。 */
        const val ACCOUNT_ID: String = "realtime_demo"
        const val SESSION_ID: String = "realtime-default"
        const val SESSION_NAME: String = "车载语音助手"
    }

    private val _boss = MutableStateFlow<BossAgent?>(null)
    val boss: StateFlow<BossAgent?> = _boss.asStateFlow()

    init {
        viewModelScope.launch {
            // 固定会话 getOrCreate：首次创建，重启后复用（JsonlMemory 从磁盘恢复记忆）
            val session = withContext(Dispatchers.IO) {
                sessionManager.getOrCreate(
                    accountId = ACCOUNT_ID,
                    sessionName = SESSION_NAME,
                    agent = AgentReference(CockpitAgent.NAME),
                    sessionId = SESSION_ID,
                )
            }
            // 配置驱动 spawn：session.memory 作为 Boss 记忆，语音委托任务共享同一实例
            _boss.value = CockpitAgent.create(session.memory)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _boss.value?.shutdown()
    }

    class Factory(private val sessionManager: SessionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RealtimeViewModel(sessionManager) as T
        }
    }
}
