package io.github.yeyi.agent.demo.realtime.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.demo.realtime.smartCockpit.CockpitAgent
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Session
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
 * 与文本聊天 demo 共享同一套智能座舱场景。会话记忆持久化，重启可续聊。
 *
 * 职责边界：UI 历史气泡由 S2S 协议侧 retrieve（不传对话 id 返回最近 20 轮
 * 完整上下文）驱动恢复，与 Boss 内核记忆（工具调用、内部计划等非 S2S 可见
 * 内容）完全解耦，前台会话与服务端权威上下文严格一致。
 */
class RealtimeViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private companion object {
        /**
         * 固定账号/会话标识。SESSION_ID 同时作为 S2S 协议侧 dialog id 使用：
         * 应用重启后 getOrCreate 复用同一会话，本地记忆续聊；S2S session.create
         * 携带同一 id 接续历史对话，服务端按该 id 恢复上下文，两侧身份合一。
         */
        const val ACCOUNT_ID: String = "realtime_demo"
        const val SESSION_ID: String = "realtime-default"
        const val SESSION_NAME: String = "车载语音助手"
    }

    private val _boss = MutableStateFlow<BossAgent?>(null)
    val boss: StateFlow<BossAgent?> = _boss.asStateFlow()

    /** 当前持久化会话：其 [Session.id] 即 S2S 协议侧 dialog id，供语音链路接续历史对话。 */
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        viewModelScope.launch {
            // 固定会话 getOrCreate：首次创建，重启后复用（JsonlMemory 从磁盘恢复 Boss 记忆）
            val session = withContext(Dispatchers.IO) {
                sessionManager.getOrCreate(
                    accountId = ACCOUNT_ID,
                    sessionName = SESSION_NAME,
                    agent = AgentReference(CockpitAgent.NAME),
                    sessionId = SESSION_ID,
                )
            }
            _session.value = session
            // 配置驱动 spawn：session.memory 作为 Boss 记忆，语音委托任务共享同一实例
            _boss.value = CockpitAgent.create(session.memory)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _boss.value?.shutdown()
    }

    class Factory(
        private val sessionManager: SessionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RealtimeViewModel(sessionManager) as T
        }
    }
}
