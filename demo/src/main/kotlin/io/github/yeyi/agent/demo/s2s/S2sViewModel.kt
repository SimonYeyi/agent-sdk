package io.github.yeyi.agent.demo.s2s

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.realtime.DelegationReply
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.android.AndroidMicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.android.AndroidSpeakerAdapter
import io.github.yeyi.agent.realtime.volc.VolcRealtimeAdapter
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class S2sViewModel(
    private val apiKey: String,
    private val boss: BossAgent,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _taskGroups = MutableStateFlow<List<TasksState>>(emptyList())
    val taskGroups: StateFlow<List<TasksState>> = _taskGroups.asStateFlow()

    init {
        // 确保 ViewModel 创建时就清空，防止前一个页面的数据残留
        _taskGroups.value = emptyList()
    }

    private var bridge: RealtimeAppliance? = null
    private var httpClient: HttpClient? = null
    private val delegation = BossDelegation(boss)
    private var sessionEventsJob: kotlinx.coroutines.Job? = null
    private var delegationRepliesJob: kotlinx.coroutines.Job? = null
    private var taskGroupsJob: kotlinx.coroutines.Job? = null

    fun startBridge() {
        sessionEventsJob?.cancel()
        delegationRepliesJob?.cancel()
        taskGroupsJob?.cancel()

        val client = HttpClient(CIO) { install(WebSockets) }
        httpClient = client
        bridge = RealtimeAppliance(
            session = RealtimeSession(client, VolcRealtimeAdapter()),
            sessionConfig = SessionConfig(
                apiKey = apiKey,
                endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                model = "1.2.6.0",
                instructions = "你是一个智能家居助手. 用自然、口语化的中文回答用户",
                voice = "zh_female_vv_jupiter_bigtts",
                tools = listOf(MusicControlTool()),
            ),
            microphone = AndroidMicrophoneAdapter(),
            speaker = AndroidSpeakerAdapter(),
            delegation = delegation,
        )
        _state.value = _state.value.copy(
            connected = true,
            messages = emptyList(),
            pendingUser = "",
            pendingAssistant = "",
            shouldExit = false,
        )
        _taskGroups.value = emptyList()
        sessionEventsJob = viewModelScope.launch { bridge?.start() }
        collectSessionEvents()
        collectDelegationReplies()
        collectTaskGroups()
    }

    fun closeBridge() {
        val b = bridge
        bridge = null
        httpClient?.close()
        httpClient = null
        sessionEventsJob?.cancel()
        delegationRepliesJob?.cancel()
        taskGroupsJob?.cancel()
        sessionEventsJob = null
        delegationRepliesJob = null
        taskGroupsJob = null
        _taskGroups.value = emptyList()
        _state.value = _state.value.copy(
            connected = false,
            messages = emptyList(),
            pendingUser = "",
            pendingAssistant = "",
        )
        viewModelScope.launch { b?.close() }
    }

    private fun collectSessionEvents() {
        viewModelScope.launch {
            bridge?.events?.collect { event ->
                when (event) {
                    is RealtimeEvent.UserTranscriptDelta -> {
                        _state.value = _state.value.copy(pendingUser = event.text)
                    }

                    is RealtimeEvent.UserTranscriptCompleted -> {
                        val text = event.text.ifBlank { _state.value.pendingUser }
                        if (text.isNotBlank()) {
                            _state.value = _state.value.copy(
                                messages = _state.value.messages + UiMessage("user", text),
                                pendingUser = "",
                            )
                        }
                    }

                    is RealtimeEvent.AssistantTextDelta -> {
                        if (_state.value.skipNextDelegationTts || event.text.startsWith("|")) {
                            // 标记为跳过，且不显示任何 TTS delta
                            _state.value = _state.value.copy(skipNextDelegationTts = true)
                        } else {
                            _state.value = _state.value.copy(
                                pendingAssistant = _state.value.pendingAssistant + event.text,
                            )
                        }
                    }

                    is RealtimeEvent.AssistantAudioStarted -> {
                        _state.value = _state.value.copy(skipNextDelegationTts = false)
                    }

                    is RealtimeEvent.AssistantAudioDone -> {
                        if (_state.value.pendingAssistant.isNotBlank()) {
                            _state.value = _state.value.copy(
                                messages = _state.value.messages + UiMessage(
                                    "assistant",
                                    _state.value.pendingAssistant.trim()
                                ),
                                pendingAssistant = "",
                            )
                        }
                    }

                    is RealtimeEvent.ResponseDone -> {
                        if (_state.value.pendingAssistant.isNotBlank()) {
                            _state.value = _state.value.copy(
                                messages = _state.value.messages + UiMessage(
                                    "assistant",
                                    _state.value.pendingAssistant.trim()
                                ),
                                pendingAssistant = "",
                            )
                        }
                    }

                    is RealtimeEvent.Disconnected -> {
                        closeBridge()
                        _state.value = _state.value.copy(shouldExit = true)
                    }

                    is RealtimeEvent.Error -> {
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
    }

    private fun collectDelegationReplies() {
        delegationRepliesJob = viewModelScope.launch {
            delegation.replies.collect { reply ->
                val text = when (reply) {
                    is DelegationReply.Confirmation -> reply.text
                    is DelegationReply.Success -> reply.text
                    is DelegationReply.Failure -> reply.message
                }
                if (text.isNotBlank()) {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + UiMessage("assistant", text),
                    )
                }
            }
        }
    }

    private fun collectTaskGroups() {
        taskGroupsJob = viewModelScope.launch {
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
    }

    override fun onCleared() {
        super.onCleared()
        closeBridge()
    }

    class Factory(
        private val apiKey: String,
        private val boss: BossAgent,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return S2sViewModel(apiKey, boss) as T
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
    val skipNextDelegationTts: Boolean = false,
)
