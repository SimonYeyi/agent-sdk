package io.github.yeyi.agent.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.core.agent.Agent
import io.github.yeyi.agent.core.agent.AgentEvent
import io.github.yeyi.agent.core.memory.InMemoryMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val agent: Agent,
) : ViewModel() {

    private val memory = InMemoryMemory()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return
        _messages.update { it + UiMessage.User(text) }
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                agent.runStream(text, memory).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            // 文本增量由 collect 之外统一处理:本 demo 简化,仅在 Final 时写入消息
                        }
                        is AgentEvent.Final -> {
                            // spec §4.7: AgentEvent.Final.message 是 ChatMessage.Assistant
                            _messages.update {
                                it + UiMessage.Assistant(
                                    text = event.message.content.orEmpty(),
                                    toolCalls = event.message.toolCalls,
                                )
                            }
                        }
                        is AgentEvent.Failed -> {
                            _messages.update { it + UiMessage.Error(event.cause.message ?: "Unknown error") }
                        }
                        is AgentEvent.ToolCallStarted,
                        is AgentEvent.ToolCallFinished -> {
                            // 仅用于 UI 指示,本 demo 简化
                        }
                    }
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
