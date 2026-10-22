package io.github.yeyi.agent.demo.team.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.demo.team.smartCockpit.CockpitAgent
import io.github.yeyi.agent.demo.team.ui.ChatMessageUi
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Conversation
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DemoViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {

    private companion object {
        /** 固定账号/会话标识：应用重启后通过 getOrCreate 复用同一会话，对话持久化续聊。 */
        const val ACCOUNT_ID: String = "team_demo"
        const val SESSION_ID: String = "cockpit-default"
        const val SESSION_NAME: String = "智能座舱"
    }

    private val _boss = MutableStateFlow<BossAgent?>(null)
    val boss: StateFlow<BossAgent?> = _boss.asStateFlow()

    private val toolCallNames = mutableMapOf<String, String>()
    private var tasksStatesJob: Job? = null
    private var reportJob: Job? = null

    private val _taskGroups = MutableStateFlow<List<TasksState>>(emptyList())
    val taskGroups: StateFlow<List<TasksState>> = _taskGroups.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
            restoreConversation(session)
            // 配置驱动 spawn：session.memory 作为 Boss 记忆，文本聊天与语音共享同一实例
            val agent = CockpitAgent.create(session.memory)
            _boss.value = agent
            wireCollectors(agent)
        }
    }

    private fun restoreConversation(session: Session) {
        viewModelScope.launch {
            val history = session.conversation.history(Conversation.PAGE_ALL)
            _messages.value = history.mapNotNull { entry ->
                entry.message.toChatMessageUi()
            }
        }
    }

    private fun wireCollectors(agent: BossAgent) {
        tasksStatesJob?.cancel()
        reportJob?.cancel()

        // Collect task states
        tasksStatesJob = viewModelScope.launch {
            agent.tasksState.collect { taskGroupState ->
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

        // Collect report events
        reportJob = viewModelScope.launch {
            agent.report.collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> {
                        toolCallNames[event.toolCall.id] = event.toolCall.name
                        appendMessage("tool", "调用工具: ${event.toolCall.name}", toolName = event.toolCall.name)
                    }
                    is AgentEvent.ToolCallEnd -> {
                        val toolName = toolCallNames.remove(event.toolCall.id) ?: "unknown"
                        val content = event.result.parts.text.takeIf { it.isNotEmpty() } ?: "完成"
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

        val agent = _boss.value ?: return

        appendMessage("user", text)
        _inputText.value = ""
        _isLoading.value = true

        viewModelScope.launch {
            agent.run(AgentQuery.text(text)).collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> {
                        toolCallNames[event.toolCall.id] = event.toolCall.name
                        appendMessage("tool", "调用工具: ${event.toolCall.name}", toolName = event.toolCall.name)
                    }
                    is AgentEvent.ToolCallEnd -> {
                        val toolName = toolCallNames.remove(event.toolCall.id) ?: "unknown"
                        val content = event.result.parts.text.takeIf { it.isNotEmpty() } ?: "完成"
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
        _boss.value?.shutdown()
    }

    class Factory(private val sessionManager: SessionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DemoViewModel(sessionManager) as T
        }
    }
}

private fun ChatMessage.toChatMessageUi(): ChatMessageUi = when (this) {
    is ChatMessage.User -> ChatMessageUi("user", parts.text)
    is ChatMessage.Assistant -> ChatMessageUi("assistant", content ?: "")
    is ChatMessage.System -> ChatMessageUi("assistant", content)
    is ChatMessage.ToolResult -> ChatMessageUi("tool", parts.text, toolName = toolName)
}
