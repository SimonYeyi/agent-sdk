package io.github.yeyi.agent.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.ToolCallRecord
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class RunMode { STREAM, BATCH }

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

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var currentAssistantId: String? = null
    private var currentAssistantText: StringBuilder? = null
    private val inProgressByCallId = mutableMapOf<String, UiMessage.ToolInProgress>()

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return
        _messages.update { it + UiMessage.User(text) }
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                val flow = when (_mode.value) {
                    RunMode.STREAM -> agent.runStream(text, InMemoryMemory())
                    RunMode.BATCH -> agent.run(text)
                }
                flow.collect { handleEvent(it) }
            } catch (t: Throwable) {
                _messages.update { it + UiMessage.Error(t.message ?: "Unknown error") }
            } finally {
                currentAssistantId = null
                currentAssistantText = null
                inProgressByCallId.clear()
                _isProcessing.value = false
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                if (currentAssistantText == null) {
                    currentAssistantId = "pending"
                    currentAssistantText = StringBuilder()
                }
                currentAssistantText?.append(event.text)
            }
            is AgentEvent.ToolCallStarted -> {
                val msg = UiMessage.ToolInProgress(event.callId, event.toolName)
                inProgressByCallId[event.callId] = msg
                _messages.update { it + msg }
            }
            is AgentEvent.ToolCallFinished -> {
                val started = inProgressByCallId.remove(event.callId)
                val record = ToolCallRecord(
                    callId = event.callId,
                    toolName = started?.toolName ?: event.callId,
                    arguments = kotlinx.serialization.json.JsonNull,
                    result = event.result,
                    timestamp = Instant.now(),
                )
                _messages.update { it + UiMessage.ToolExecution(event.callId, record) }
            }
            is AgentEvent.ToolCallRecorded -> {
                // v1.0 back-fill internal event; UI already rendered via ToolCallFinished
            }
            is AgentEvent.Final -> {
                val text = currentAssistantText?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    _messages.update { it + UiMessage.Assistant(text) }
                }
                currentAssistantId = null
                currentAssistantText = null
            }
            is AgentEvent.Failed -> {
                _messages.update { it + UiMessage.Error(event.cause.message ?: "Unknown error") }
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
