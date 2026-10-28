package io.github.yeyi.agent.demo.realtime.s2s

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.demo.realtime.log
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.android.AndroidMicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.android.AndroidSpeakerAdapter
import io.github.yeyi.agent.realtime.volc.VolcRealtimeAdapter
import io.github.yeyi.agent.realtime.volc.VolcRealtimeAppliance
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class S2sViewModel(
    private val applicationContext: android.content.Context,
    private val apiKey: String,
    private val boss: BossAgent,
    /** S2S 协议侧 dialog id：session.create 携带同一 id 接续历史对话。 */
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _taskGroups = MutableStateFlow<List<TasksState>>(emptyList())
    val taskGroups: StateFlow<List<TasksState>> = _taskGroups.asStateFlow()

    init {
        _taskGroups.value = emptyList()
    }

    private var bridge: RealtimeAppliance? = null
    private var httpClient: HttpClient? = null
    private val delegation = BossDelegation(boss)
    private var bridgeJob: Job? = null
    private var sessionCollectJob: Job? = null
    private var taskGroupsCollectJob: Job? = null

    fun startBridge() {
        bridgeJob?.cancel()
        sessionCollectJob?.cancel()
        taskGroupsCollectJob?.cancel()

        val client = HttpClient(CIO) { install(WebSockets) }
        httpClient = client
        bridge = RealtimeAppliance(
            session = RealtimeSession(client, VolcRealtimeAdapter()),
            sessionConfig = SessionConfig(
                apiKey = apiKey,
                endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                model = "1.2.6.0",
                instructions = "你是一个车载语音助手. 用自然、口语化的中文回答用户",
                voice = "zh_female_vv_jupiter_bigtts",
                tools = listOf(MusicControlTool()),
                sessionId = sessionId,
            ),
            microphone = AndroidMicrophoneAdapter(),
            speaker = AndroidSpeakerAdapter(),
            delegation = delegation,
        )
        /*bridge = VolcRealtimeAppliance(
            context = applicationContext,
            sessionConfig = SessionConfig(
                apiKey = apiKey,
                endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                model = "1.2.6.0",
                instructions = "你是一个车载语音助手. 用自然、口语化的中文回答用户",
                voice = "zh_female_vv_jupiter_bigtts",
                tools = listOf(MusicControlTool()),
                // 携带持久化的 dialog id 接续历史对话：服务端按该 id 恢复最近 20 轮上下文
                sessionId = sessionId,
            ),
            speaker = AndroidSpeakerAdapter(),
            delegation = delegation
        )*/
        // 保留恢复的初始历史消息，仅切换连接状态
        _state.value = _state.value.copy(connected = true)
        _taskGroups.value = emptyList()
        bridgeJob = viewModelScope.launch { bridge?.start() }
        sessionCollectJob = launchCollectSessionEvents()
        taskGroupsCollectJob = launchCollectTaskGroups()
    }

    private fun closeBridge() {
        bridgeJob?.cancel()
        bridgeJob = null
        sessionCollectJob?.cancel()
        sessionCollectJob = null
        taskGroupsCollectJob?.cancel()
        taskGroupsCollectJob = null

        val b = bridge
        bridge = null
        httpClient?.close()
        httpClient = null

        // 静默释放资源：closeBridge 只由 onCleared 兜底调用（Activity 销毁自动触发），
        // 屏面即将销毁，改动 UI 状态只会造成无意义的闪烁，因此不触碰 _state/_taskGroups。
        if (b != null) {
            // 终端清理不得依赖 viewModelScope：androidx 在 ViewModel.clear() 中先取消
            // viewModelScope 再回调 onCleared，onCleared 路径下 launch 会成为空操作。
            // 独立 scope 保证 SpeechEngine/AudioTrack 等资源无论以何种方式销毁 Activity
            // （返回键、最近任务滑动、系统回收）都能被释放；bridge 已置 null 保证幂等。
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { b.close() }
        }
    }

    private fun launchCollectSessionEvents(): Job = viewModelScope.launch {
        bridge?.events?.collect { event ->
            log.info("event: $event")
            when (event) {
                is RealtimeEvent.UserTranscriptStarted -> {
                    // 新一轮用户语音开始：先提交上一轮未完成的助手气泡
                    if (_state.value.pendingAssistant.isNotBlank()) {
                        submitAssistant(_state.value.pendingAssistant)
                    }
                }

                is RealtimeEvent.UserTranscriptDelta -> {
                    _state.value = _state.value.copy(pendingUser = event.text)
                }

                is RealtimeEvent.UserTranscriptCompleted -> {
                    submitUser(event.text.ifBlank { _state.value.pendingUser })
                }

                is RealtimeEvent.AssistantTextDelta -> {
                    _state.value = _state.value.copy(
                        pendingAssistant = _state.value.pendingAssistant + event.text,
                    )
                }

                is RealtimeEvent.AssistantTextDone -> {
                    // 用 event.text 替换之前的累加预览（服务器给的最终权威文本）
                    submitAssistant(event.text)
                }

                is RealtimeEvent.AssistantAudioStarted -> {
                }

                is RealtimeEvent.AssistantAudioDone -> {
                }

                is RealtimeEvent.ResponseDone -> {
                    // 兜底：若 TextDone 未触发（协议差异），用累加的 pendingAssistant 提交
                    if (_state.value.pendingAssistant.isNotBlank()) {
                        submitAssistant(_state.value.pendingAssistant)
                    }
                }

                is RealtimeEvent.ConversationHistoryRetrieved -> {
                    // 服务端权威历史（最近 20 轮）：仅渲染 user/assistant 对话文本，
                    // 工具调用等非对话条目不参与 UI 气泡
                    val history = event.items
                        .filter { it.role == "user" || it.role == "assistant" }
                        .map { UiMessage(it.role!!, it.text ?: "") }
                        .filter { it.content.isNotBlank() }
                    _state.value = _state.value.copy(messages = history)
                }

                is RealtimeEvent.Disconnected -> {
                    _state.value = _state.value.copy(shouldExit = true)
                }

                is RealtimeEvent.Error -> {
                    log.error(
                        "RealtimeEvent.Error: code=${event.code} message=${event.message} isFatal=${event.isFatal}",
                    )
                    _state.value = _state.value.copy(
                        pendingAssistant = "",
                        pendingUser = "",
                        shouldExit = true,
                    )
                }

                else -> Unit
            }
        }
    }

    /** 提交一条用户气泡。 */
    private fun submitUser(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        _state.value = _state.value.copy(
            messages = _state.value.messages + UiMessage("user", trimmed),
            pendingUser = "",
        )
    }

    /** 提交一条助手气泡。 */
    private fun submitAssistant(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        _state.value = _state.value.copy(
            messages = _state.value.messages + UiMessage("assistant", trimmed),
            pendingAssistant = "",
        )
    }

    private fun launchCollectTaskGroups(): Job = viewModelScope.launch {
        boss.tasksState.collect { taskGroupState ->
            val currentList = _taskGroups.value.toMutableList()
            val existingIndex =
                currentList.indexOfFirst { it.roundId == taskGroupState.roundId }
            if (existingIndex >= 0) {
                currentList[existingIndex] = taskGroupState
            } else {
                currentList.add(taskGroupState)
            }
            _taskGroups.value = currentList.toList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeBridge()
    }

    class Factory(
        private val applicationContext: android.content.Context,
        private val apiKey: String,
        private val boss: BossAgent,
        private val sessionId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return S2sViewModel(
                applicationContext = applicationContext,
                apiKey = apiKey,
                boss = boss,
                sessionId = sessionId,
            ) as T
        }
    }
}

data class UiMessage(
    val role: String,
    val content: String,
)

data class UiState(
    val connected: Boolean = false,
    val messages: List<UiMessage> = emptyList(),
    val pendingUser: String = "",
    val pendingAssistant: String = "",
    val shouldExit: Boolean = false,
)
