package io.github.yeyi.agent.demo.s2s

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.demo.log
import io.github.yeyi.agent.realtime.DelegationReply
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class S2sViewModel(
    private val applicationContext: android.content.Context,
    private val apiKey: String,
    private val boss: BossAgent,
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
    private var delegationCollectJob: Job? = null
    private var taskGroupsCollectJob: Job? = null

    fun startBridge() {
        bridgeJob?.cancel()
        sessionCollectJob?.cancel()
        delegationCollectJob?.cancel()
        taskGroupsCollectJob?.cancel()

        val client = HttpClient(CIO) { install(WebSockets) }
        httpClient = client
        /*bridge = RealtimeAppliance(
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
        )*/
        bridge = VolcRealtimeAppliance(
            context = applicationContext,
            sessionConfig = SessionConfig(
                apiKey = apiKey,
                endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                model = "1.2.6.0",
                instructions = "你是一个智能家居助手. 用自然、口语化的中文回答用户",
                voice = "zh_female_vv_jupiter_bigtts",
                tools = listOf(MusicControlTool()),
            ),
            speaker = AndroidSpeakerAdapter(),
        )
        _state.value = UiState(connected = true)
        _taskGroups.value = emptyList()
        bridgeJob = viewModelScope.launch { bridge?.start() }
        sessionCollectJob = launchCollectSessionEvents()
        delegationCollectJob = launchCollectDelegationReplies()
        taskGroupsCollectJob = launchCollectTaskGroups()
    }

    fun closeBridge() {
        bridgeJob?.cancel()
        bridgeJob = null
        sessionCollectJob?.cancel()
        sessionCollectJob = null
        delegationCollectJob?.cancel()
        delegationCollectJob = null
        taskGroupsCollectJob?.cancel()
        taskGroupsCollectJob = null

        val b = bridge
        bridge = null
        httpClient?.close()
        httpClient = null

        _taskGroups.value = emptyList()
        _state.value = UiState(connected = false)
        viewModelScope.launch { b?.close() }
    }

    private fun launchCollectSessionEvents(): Job = viewModelScope.launch {
        bridge?.events?.collect { event ->
            when (event) {
                is RealtimeEvent.UserTranscriptStarted -> {
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

    private fun launchCollectDelegationReplies(): Job = viewModelScope.launch {
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return S2sViewModel(applicationContext, apiKey, boss) as T
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
