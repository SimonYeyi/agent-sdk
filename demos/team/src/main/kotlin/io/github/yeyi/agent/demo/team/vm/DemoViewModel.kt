package io.github.yeyi.agent.demo.team.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.demo.team.smartCockpit.CockpitAgent
import io.github.yeyi.agent.demo.team.ui.ChatMessageUi
import io.github.yeyi.agent.demo.team.ui.PendingInput
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Conversation
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TasksState
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** 流式草稿气泡下标：TextDelta 逐字累积于此，ToolCallExplanation 段收尾、Final 总收尾、Failed 兜底清除。 */
    private var streamingAssistantIndex: Int? = null

    /**
     * 发送后待确认的指令队列：指令不立即渲染为用户气泡，先在此排队等待
     * 对应 [AgentEvent.Initial] 确认，避免 BossAgent 报告轮次进行中指令被
     * 排队时，重启后记忆顺序（报告→指令）与运行时显示顺序颠倒。
     * queued=true 的项显示在输入框上方的悬浮列表，提示"指令正在队列中"。
     */
    private val pendingQueue = PendingInputQueue()
    private val _pendingInputs = MutableStateFlow<List<PendingInput>>(emptyList())
    val pendingInputs: StateFlow<List<PendingInput>> = _pendingInputs.asStateFlow()

    /** 指令发出后等待 Initial 确认的超时窗口：超时未确认即转入排队悬浮列表。 */
    private val PENDING_CONFIRM_TIMEOUT_MS = 200L

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
            _messages.value = history.mapNotNull { entry -> entry.toChatMessageUi() }
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
                        // 报告轮次非流式运行，不会出现 TextDelta；过渡语由
                        // ToolCallExplanation 以完整文本承载，无需增量累积。
                    }
                    is AgentEvent.Initial -> {
                        // 带 label 的 query（BossAgent 任务系统报告）渲染为"系统"气泡；
                        // 普通用户消息已由输入框展示，跳过。
                        event.query.label?.let { appendMessage("system", event.query.parts.text) }
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

        // 不立即渲染用户气泡：BossAgent 可能正处于任务报告轮次，指令进入
        // scheduler 队列待报告完成后才执行；立即显示会导致运行时顺序
        // （指令→报告）与重启后记忆恢复顺序（报告→指令）颠倒。
        // 指令先入待确认队列，收到对应 Initial 事件后再落为用户气泡。
        addPendingInput(text)
        _inputText.value = ""
        _isLoading.value = true

        viewModelScope.launch {
            agent.run(AgentQuery.text(text)).collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStart -> handleToolCallStart(event.toolCall)
                    is AgentEvent.ToolCallEnd -> handleToolCallEnd(event.toolCall, event.result)
                    // ToolCallExplanation 与 Final 统一走 finishStreamingSegment：
                    // 两者都是携带权威完整文本的流式段结束信号，收尾骨架完全一致。
                    // Final 的完整文本必然对应刚流式累积完成的草稿（纯文本轮草稿在
                    // 列表末尾原位收尾；allAsync 短路轮为 content=null 空响应，过渡语
                    // 已由 ToolCallExplanation 原位收尾，不归位不渲染），无需归位动作。
                    is AgentEvent.ToolCallExplanation -> finishStreamingSegment(event.text)
                    is AgentEvent.TextDelta -> appendStreamingText(event.text)
                    is AgentEvent.Initial -> {
                        // 指令开始执行：从待确认队列移除并落为用户气泡（带 label 的
                        // Initial 属于报告轮次，走 report 流渲染系统气泡，不在此处理）。
                        if (event.query.label == null) {
                            confirmPendingInput(text)
                        }
                    }
                    is AgentEvent.Final -> {
                        finishStreamingSegment((event.result as? AgentResult)?.message?.content)
                        // 轮次收尾：loading 归位是 Final/Failed 独有的轮次副作用，
                        // 与流式气泡归位正交，放在事件分支而非流式收尾方法内。
                        _isLoading.value = false
                    }
                    is AgentEvent.Failed -> {
                        // 兜底：失败也把指令落为用户气泡，避免悬浮队列滞留
                        clearStreamingDraft()
                        confirmPendingInput(text)
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
     * 流式文本累积：追加到草稿气泡（列表最后一条），无草稿时新建。
     * 工具气泡会追加在草稿之后，因此含工具轮中草稿并不总是最后一条。
     */
    private fun appendStreamingText(delta: String) {
        val index = streamingAssistantIndex
        val base = index?.let { _messages.value.getOrNull(it)?.content } ?: ""
        val text = base + delta
        if (index != null && index < _messages.value.size) {
            _messages.value = _messages.value.toMutableList().also { it[index] = it[index].copy(content = text) }
        } else {
            streamingAssistantIndex = _messages.value.size
            _messages.value = _messages.value + ChatMessageUi("assistant", text)
        }
    }

    /**
     * 流式段统一收尾：ToolCallExplanation 与 Final 都是携带权威完整文本的流式段结束信号，
     * 收尾骨架完全一致：
     * - 草稿存在时原位替换为权威完整文本。Final 的完整文本必然对应刚流式累积完成的
     *   草稿（纯文本轮草稿在列表末尾原位收尾；allAsync 短路轮为 content=null 空响应时
     *   保留草稿现状——过渡语已由 ToolCallExplanation 原位收尾，不归位不渲染），
     *   因此无需任何"移除草稿+追加末尾"的归位动作。
     * - 草稿缺失（非流式路径或增量未到达）时按完整文本新建气泡兜底。
     * - 收尾后重置草稿下标，下一段从列表末尾新起草稿，保证段间气泡顺序与记忆写入顺序一致。
     */
    private fun finishStreamingSegment(authoritativeText: String?) {
        val index = streamingAssistantIndex
        val finalText = authoritativeText?.takeIf { it.isNotBlank() }
        if (index != null && index < _messages.value.size) {
            _messages.value = _messages.value.toMutableList().also {
                it[index] = it[index].copy(content = finalText ?: it[index].content)
            }
            streamingAssistantIndex = null
        } else if (finalText != null) {
            appendMessage("assistant", finalText)
        }
    }

    /** 清除流式草稿气泡（失败路径，避免残留未完成的半截文本）。 */
    private fun clearStreamingDraft() {
        streamingAssistantIndex?.let { idx ->
            if (idx < _messages.value.size) {
                _messages.value = _messages.value.toMutableList().also { it.removeAt(idx) }
            }
            streamingAssistantIndex = null
        }
    }

    /**
     * 指令入队待确认：先标记未排队；200ms 内未收到对应 [AgentEvent.Initial]
     * 则转为排队中，UI 在输入框上方展示悬浮列表提示指令正在等待执行。
     */
    private fun addPendingInput(text: String) {
        val index = pendingQueue.add(text)
        refreshPendingInputs()
        viewModelScope.launch {
            delay(PENDING_CONFIRM_TIMEOUT_MS)
            pendingQueue.markQueued(index)
            refreshPendingInputs()
        }
    }

    /**
     * 指令确认：Agent 开始执行该指令（收到对应 Initial 事件），
     * 从待确认队列移除并渲染为用户气泡；悬浮队列清空后 UI 自动关闭。
     */
    private fun confirmPendingInput(text: String) {
        if (pendingQueue.confirm(text)) {
            appendMessage("user", text)
        }
        refreshPendingInputs()
    }

    /** 将队列状态机快照同步到 UI 状态流。 */
    private fun refreshPendingInputs() {
        _pendingInputs.value = pendingQueue.snapshot()
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
 * 消息恢复渲染；带 report label 的 User 消息是 BossAgent 任务系统报告，渲染为"系统"气泡；
 * 助手消息 content 为空（纯工具轮等）时不渲染气泡，避免空气泡。
 */
private fun MemoryEntry.toChatMessageUi(): ChatMessageUi? = when (val msg = message) {
    is ChatMessage.User -> if (label == BossAgent.REPORT_LABEL) {
        ChatMessageUi("system", msg.parts.text)
    } else {
        ChatMessageUi("user", msg.parts.text)
    }
    is ChatMessage.Assistant -> msg.content?.takeIf { it.isNotBlank() }
        ?.let { ChatMessageUi("assistant", it) }
    is ChatMessage.System -> ChatMessageUi("assistant", msg.content)
    // 工具调用在运行时最终只保留结果气泡（以工具名为标签、结果为内容），
    // 恢复渲染格式与运行时替换后的结果一致。
    is ChatMessage.ToolResult -> ChatMessageUi(
        "tool",
        msg.parts.text.takeIf { it.isNotEmpty() } ?: "完成",
        toolName = msg.toolName,
    )
}
