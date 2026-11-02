package io.github.yeyi.agent.demo.agent.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.demo.agent.demo.DemoAgentFactory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RunMode { STREAM, BATCH }

data class LiveBubble(val id: String, val text: String)

class ChatViewModel(
    private val agentFactory: (Memory) -> Agent = { memory -> DemoAgentFactory.create(memory = memory) }
) : ViewModel() {

    private val _mode = MutableStateFlow(RunMode.STREAM)
    val mode: StateFlow<RunMode> = _mode.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _liveBubble = MutableStateFlow<LiveBubble?>(null)
    val liveBubble: StateFlow<LiveBubble?> = _liveBubble.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val inProgressByCallId = mutableMapOf<String, UiMessage.ToolInProgress>()

    private var memory: Memory = io.github.yeyi.agent.memory.InMemoryMemory()
    private var agent: Agent = agentFactory(memory)

    private var runJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { reloadMessages() }
    }

    private suspend fun reloadMessages() {
        val history = memory.history()
        _messages.value = history
            .filter { entry ->
                val msg = entry.message
                msg is ChatMessage.User ||
                (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank()) ||
                (msg is ChatMessage.System && !msg.content.isNullOrBlank())
            }
            .map { it.toUiMessage() }
    }

    /**
     * 记忆条目 → UI 消息。
     *
     * 带 "error" label 的助手消息是运行失败写入的记忆，渲染为错误气泡，
     * 与运行时 [AgentEvent.Failed] 的气泡样式一致 —— 记忆压缩后重建列表
     * 不把错误降级成普通助手气泡。
     */
    private fun MemoryEntry.toUiMessage(): UiMessage {
        val msg = message
        if (msg is ChatMessage.Assistant && label == "error") {
            return UiMessage.Error(cause = msg.content ?: "Unknown error", id = nextUiId())
        }
        return msg.toUiMessage()
    }

    private fun ChatMessage.toUiMessage(): UiMessage {
        return when (this) {
            is ChatMessage.User -> UiMessage.User(
                text = parts.text,
                id = nextUiId(),
            )
            is ChatMessage.Assistant -> UiMessage.Assistant(text = content ?: "", id = nextUiId())
            is ChatMessage.System -> UiMessage.Assistant(text = content, id = nextUiId())
            is ChatMessage.ToolResult -> UiMessage.ToolExecution(
                callId = toolCallId,
                toolName = toolName,
                result = ToolExecutionResult(parts, isError)
            )
        }
    }

    fun setMode(m: RunMode) {
        _mode.value = m
    }

    fun clearMessages() {
        // "New" 的语义是开新会话：先清空 UI，再作废旧 agent。
        // 旧 agent 必须 shutdown：架构 2 下回合跑在 agent 私有 scope，
        // 只掐收集会让执行体在无人观察时烧到自然终局。
        val oldAgent = agent
        runJob?.cancel()
        runJob = null
        _messages.value = emptyList()
        _liveBubble.value = null
        inProgressByCallId.clear()
        memory = io.github.yeyi.agent.memory.InMemoryMemory()
        agent = agentFactory(memory)
        (oldAgent as? Agent.Shutdownable)?.shutdown()
    }

    /**
     * VM 内部单调计数器,为 LazyColumn 生成稳定唯一的 key。
     * 不暴露、不带语义前缀——用户不可见,LazyColumn 只看唯一性。
     */
    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return

        // run() 调用即启动回合（架构 2）——计划在点击回调里同步启动，
        // 而不是等 viewModelScope 的协程被调度：清空/关闭页面若抢在协程开跑前
        // 引用旧 agent，这一轮就成了无人观察的在途回合。
        val flow = when (_mode.value) {
            RunMode.STREAM -> (agent as? Streamable)?.runStream(AgentQuery.text(text))
                ?: agent.run(AgentQuery.text(text))
            RunMode.BATCH -> agent.run(AgentQuery.text(text))
        }
        _isProcessing.value = true

        runJob = viewModelScope.launch {
            try {
                // 流式是可选能力：Agent 未实现 Streamable 时降级为批式路径。
                flow.collect { handleEvent(it) }
            } catch (t: Throwable) {
                _messages.update {
                    it + UiMessage.Error(t.message ?: "Unknown error", id = nextUiId())
                }
            } finally {
                _liveBubble.value = null
                inProgressByCallId.clear()
                _isProcessing.value = false
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Initial -> {
                _messages.update { it + UiMessage.User(
                    text = event.query.parts.text,
                    id = nextUiId(),
                ) }
                _liveBubble.value = null
            }
            is AgentEvent.ToolCallExplanation -> {
                event.text?.let { text ->
                    _messages.update { it + UiMessage.Assistant(text, id = nextUiId()) }
                }
                _liveBubble.value = null
            }
            is AgentEvent.TextDelta -> {
                _liveBubble.update { current ->
                    val next = (current?.text ?: "") + event.text
                    if (current == null) LiveBubble(nextUiId(), next) else current.copy(text = next)
                }
            }
            is AgentEvent.ToolCallStart -> {
                val msg = UiMessage.ToolInProgress(event.toolCall.id, event.toolCall.name)
                inProgressByCallId[event.toolCall.id] = msg
                _messages.update { it + msg }
            }
            is AgentEvent.ToolCallEnd -> {
                val started = inProgressByCallId.remove(event.toolCall.id)
                _messages.update {
                    it + UiMessage.ToolExecution(
                        callId = event.toolCall.id,
                        toolName = started?.toolName ?: event.toolCall.id,
                        result = event.result,
                    )
                }
            }
            is AgentEvent.Final -> {
                _messages.update { it + UiMessage.Assistant(event.result.message.content ?: "", id = nextUiId()) }
                _liveBubble.value = null
            }
            is AgentEvent.Failed -> {
                _messages.update {
                    it + UiMessage.Error(event.cause.message ?: "Unknown error", id = nextUiId())
                }
                _liveBubble.value = null
            }
            is AgentEvent.Canceled -> {
                // 终局三选一里的第三种：回合被取消（清空消息 / 关闭页面）——
                // 不是运行失败，渲染打断气泡。
                _messages.update {
                    it + UiMessage.Assistant("（已打断）", id = nextUiId())
                }
                _liveBubble.value = null
            }
            is AgentEvent.MemoryCompressing -> {
                // 等待压缩完成
            }
            is AgentEvent.MemoryCompressed -> {
                viewModelScope.launch { reloadMessages() }
            }
        }
    }
}
