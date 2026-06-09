package io.github.yeyi.agent.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RunMode { STREAM, BATCH }

/**
 * UI 渲染时拼接在 [_messages] 末尾的瞬时态——仅在 STREAM 模式累积阶段存在。
 *
 * @param id 当前轮唯一 id(sentinel "a-live-{turn}"),与 Final 提交的 [UiMessage.Assistant]
 *   共用,确保 LazyColumn 把 live → committed 视为同 item 的内容变化,无视觉跳动。
 */
data class LiveBubble(val id: String, val text: String)

class ChatViewModel(
    private val agent: Agent,
) : ViewModel() {

    private val _mode = MutableStateFlow(RunMode.STREAM)
    val mode: StateFlow<RunMode> = _mode.asStateFlow()

    fun setMode(m: RunMode) {
        _mode.value = m
    }

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _liveBubble = MutableStateFlow<LiveBubble?>(null)
    val liveBubble: StateFlow<LiveBubble?> = _liveBubble.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val inProgressByCallId = mutableMapOf<String, UiMessage.ToolInProgress>()

    private var turnCounter: Int = 0

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return
        turnCounter++
        _messages.update { it + UiMessage.User(text) }
        _liveBubble.value = null
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                val flow = when (_mode.value) {
                    RunMode.STREAM -> agent.runStream(text)
                    RunMode.BATCH -> agent.run(text)
                }
                flow.collect { handleEvent(it) }
            } catch (t: Throwable) {
                _messages.update { it + UiMessage.Error(t.message ?: "Unknown error") }
            } finally {
                _liveBubble.value = null
                inProgressByCallId.clear()
                _isProcessing.value = false
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                val turnId = "a-live-${turnCounter}"
                _liveBubble.update { current ->
                    val next = (current?.text ?: "") + event.text
                    if (current == null) LiveBubble(turnId, next) else current.copy(text = next)
                }
            }
            is AgentEvent.ToolCallStarted -> {
                val msg = UiMessage.ToolInProgress(event.callId, event.toolName)
                inProgressByCallId[event.callId] = msg
                _messages.update { it + msg }
            }
            is AgentEvent.ToolCallFinished -> {
                val started = inProgressByCallId.remove(event.callId)
                _messages.update {
                    it + UiMessage.ToolExecution(
                        callId = event.callId,
                        toolName = started?.toolName ?: event.callId,
                        result = event.result,
                    )
                }
            }
            is AgentEvent.Final -> {
                val live = _liveBubble.value
                val text = live?.text?.takeIf { it.isNotEmpty() }
                    ?: event.result.message.content.orEmpty()
                if (text.isNotEmpty()) {
                    val id = live?.id ?: "a-live-${turnCounter}"
                    _messages.update { it + UiMessage.Assistant(text, id = id) }
                }
                _liveBubble.value = null
            }
            is AgentEvent.Failed -> {
                _messages.update { it + UiMessage.Error(event.cause.message ?: "Unknown error") }
                _liveBubble.value = null
            }
        }
    }
}

class ChatViewModelFactory(
    private val agent: Agent,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(agent) as T
    }
}
