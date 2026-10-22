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
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Conversation
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import io.github.yeyi.agent.tool.ToolExecutionResult
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

    /** toolCall.id -> 对应"执行中"气泡在 _messages 中的下标，工具结果到达时原位替换。 */
    private val pendingToolBubbles = mutableMapOf<String, Int>()
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
            // 运行时工具调用最终只保留结果气泡（"执行中"气泡被结果替换），
            // memory 持久化的 ToolResult 即最终结果，恢复渲染与运行时一致。
            _messages.value = history.mapNotNull { entry -> entry.message.toChatMessageUi() }
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
                    is AgentEvent.ToolCallStart -> handleToolCallStart(event.toolCall)
                    is AgentEvent.ToolCallEnd -> handleToolCallEnd(event.toolCall, event.result)
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
                        // content 为空（纯工具轮等）不渲染助手气泡，避免空气泡
                        result?.message?.content?.takeIf { it.isNotBlank() }
                            ?.let { appendMessage("assistant", it) }
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
                    is AgentEvent.ToolCallStart -> handleToolCallStart(event.toolCall)
                    is AgentEvent.ToolCallEnd -> handleToolCallEnd(event.toolCall, event.result)
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
                        // content 为空（纯工具轮等）不渲染助手气泡，避免空气泡
                        result?.message?.content?.takeIf { it.isNotBlank() }
                            ?.let { appendMessage("assistant", it) }
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

    /**
     * 工具开始：追加"调用工具: xxx"气泡并记录下标，等待 [handleToolCallEnd] 原位替换。
     */
    private fun handleToolCallStart(toolCall: ToolCall) {
        val name = toolCall.name
        pendingToolBubbles[toolCall.id] = _messages.value.size
        _messages.value = _messages.value + ChatMessageUi("tool", "调用工具: $name")
    }

    /**
     * 工具结果到达：把 [handleToolCallStart] 记录的"调用工具"气泡原位替换为执行结果，
     * 以工具名为气泡标签、结果为内容，明确结果来源工具；
     * 找不到对应气泡（异常路径）时直接追加，保证结果不丢失。
     */
    private fun handleToolCallEnd(toolCall: ToolCall, result: ToolExecutionResult) {
        val name = toolCall.name
        val content = result.parts.text.takeIf { it.isNotEmpty() } ?: "完成"
        val replacement = ChatMessageUi("tool", content, toolName = name)
        val index = pendingToolBubbles.remove(toolCall.id)
        if (index != null && index < _messages.value.size) {
            _messages.value = _messages.value.toMutableList().also { it[index] = replacement }
        } else {
            _messages.value = _messages.value + replacement
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

/**
 * 消息恢复渲染；助手消息 content 为空（纯工具轮等）时不渲染气泡，避免空气泡。
 */
private fun ChatMessage.toChatMessageUi(): ChatMessageUi? = when (this) {
    is ChatMessage.User -> ChatMessageUi("user", parts.text)
    is ChatMessage.Assistant -> content?.takeIf { it.isNotBlank() }?.let { ChatMessageUi("assistant", it) }
    is ChatMessage.System -> ChatMessageUi("assistant", content)
    // 工具调用在运行时最终只保留结果气泡（以工具名为标签、结果为内容），
    // 恢复渲染格式与运行时替换后的结果一致。
    is ChatMessage.ToolResult -> ChatMessageUi(
        "tool",
        parts.text.takeIf { it.isNotEmpty() } ?: "完成",
        toolName = toolName,
    )
}
