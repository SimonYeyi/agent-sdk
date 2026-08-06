package io.github.yeyi.agent.demo.team.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.demo.team.smartHome.SmartHomeAgent
import io.github.yeyi.agent.demo.team.ui.ChatMessageUi
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DemoViewModel(
    private val llmProvider: LlmProvider
) : ViewModel() {

    private var bossAgent: BossAgent? = null
    private val toolCallNames = mutableMapOf<String, String>()
    private var tasksStatesJob: Job? = null
    private var continuationsJob: Job? = null

    private val _taskGroups = MutableStateFlow<List<TasksState>>(emptyList())
    val taskGroups: StateFlow<List<TasksState>> = _taskGroups.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        initializeAgent()
    }

    private fun initializeAgent() {
        tasksStatesJob?.cancel()
        continuationsJob?.cancel()

        bossAgent = SmartHomeAgent.create(llmProvider)

        // Collect task states
        tasksStatesJob = viewModelScope.launch {
            bossAgent?.tasksState?.collect { taskGroupState ->
                val currentList = _taskGroups.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.roundId == taskGroupState.roundId }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = taskGroupState
                } else {
                    currentList.add(taskGroupState)
                }
                _taskGroups.value = currentList.toList()
            }
        }

        // Collect continuation events
        continuationsJob = viewModelScope.launch {
            bossAgent?.continuations?.collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> {
                        toolCallNames[event.callId] = event.toolName
                        appendMessage("tool", "调用工具: ${event.toolName}", toolName = event.toolName)
                    }
                    is AgentEvent.ToolCallEnd -> {
                        val toolName = toolCallNames.remove(event.callId) ?: "unknown"
                        val content = event.result.content?.toString() ?: "完成"
                        appendMessage("tool", "[$toolName] $content", toolName = toolName)
                    }
                    is AgentEvent.ToolCallExplanation -> {
                        val text = event.text ?: ""
                        if (text.isNotEmpty()) {
                            appendMessage("assistant", "💬 $text")
                        }
                    }
                    is AgentEvent.TextDelta -> {
                        // Skip, handled by Final
                    }
                    is AgentEvent.Initial -> {
                        // Skip, user input already shown
                    }
                    is AgentEvent.Final -> {
                        val result = event.result as? AgentResult
                        val content = result?.message?.content ?: ""
                        appendMessage("assistant", content)
                        _isLoading.value = false
                    }
                    is AgentEvent.Failed -> {
                        appendMessage("assistant", "错误: ${event.cause.message}")
                        _isLoading.value = false
                    }
                    else -> {
                        // Other events ignored in main chat
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun onSend() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        appendMessage("user", text)
        _inputText.value = ""
        _isLoading.value = true

        viewModelScope.launch {
            bossAgent?.run(text)?.collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> {
                        toolCallNames[event.callId] = event.toolName
                        appendMessage("tool", "调用工具: ${event.toolName}", toolName = event.toolName)
                    }
                    is AgentEvent.ToolCallEnd -> {
                        val toolName = toolCallNames.remove(event.callId) ?: "unknown"
                        val content = event.result.content?.toString() ?: "完成"
                        appendMessage("tool", "[$toolName] $content", toolName = toolName)
                    }
                    is AgentEvent.ToolCallExplanation -> {
                        val text = event.text ?: ""
                        if (text.isNotEmpty()) {
                            appendMessage("assistant", "💬 $text")
                        }
                    }
                    is AgentEvent.TextDelta -> {
                        // Text deltas are handled by Final, skip here
                    }
                    is AgentEvent.Initial -> {
                        // User input already shown, skip
                    }
                    is AgentEvent.Final -> {
                        val result = event.result as? AgentResult
                        val content = result?.message?.content ?: ""
                        appendMessage("assistant", content)
                        _isLoading.value = false
                    }
                    is AgentEvent.Failed -> {
                        appendMessage("assistant", "错误: ${event.cause.message}")
                        _isLoading.value = false
                    }
                    else -> {
                        // Other events ignored
                    }
                }
            }
        }
    }

    private fun appendMessage(role: String, content: String, isLoading: Boolean = false, toolName: String? = null) {
        _messages.value = _messages.value + ChatMessageUi(role, content, isLoading, toolName)
    }

    override fun onCleared() {
        super.onCleared()
        bossAgent?.shutdown()
    }

    class Factory(private val llmProvider: LlmProvider) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DemoViewModel(llmProvider) as T
        }
    }
}
