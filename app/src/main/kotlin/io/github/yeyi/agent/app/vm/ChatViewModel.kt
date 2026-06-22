package io.github.yeyi.agent.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RunMode { STREAM, BATCH }

/**
 * UI 渲染时拼接在 [_messages] 末尾的瞬时态——仅在 STREAM 模式累积阶段存在。
 *
 * @param id VM 计数器分配的唯一 LazyColumn key,Final 提交的 [UiMessage.Assistant]
 *   沿用,保证 LazyColumn 把 live → committed 视为同 item 的内容变化,无视觉跳动。
 */
data class LiveBubble(val id: String, val text: String)

class ChatViewModel(
    private val agentFactory: () -> Agent,
    private val memory: Memory? = null,
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

    private var agent: Agent = agentFactory()

    init {
        memory?.let { mem ->
            viewModelScope.launch {
                reloadMessages(mem)
            }
        }
    }

    private suspend fun reloadMessages(mem: Memory) {
        val history = mem.history()
        _messages.value = history
            .filter { msg ->
                msg is ChatMessage.User ||
                (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank()) ||
                (msg is ChatMessage.System && !msg.content.isNullOrBlank())
            }
            .map { it.toUiMessage() }
    }

    private fun ChatMessage.toUiMessage(): UiMessage {
        return when (this) {
            is ChatMessage.User -> UiMessage.User(content, id = nextUiId())
            is ChatMessage.Assistant -> UiMessage.Assistant(text = content ?: "", id = nextUiId())
            is ChatMessage.System -> UiMessage.Assistant(text = content, id = nextUiId())
            is ChatMessage.ToolResult -> UiMessage.ToolExecution(
                callId = toolCallId,
                toolName = toolName,
                result = io.github.yeyi.agent.tool.ToolExecutionResult(
                    content = content,
                    isError = isError
                )
            )
        }
    }

    fun setMode(m: RunMode) {
        _mode.value = m
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _liveBubble.value = null
        inProgressByCallId.clear()
        agent = agentFactory()
    }

    /**
     * VM 内部单调计数器,为 LazyColumn 生成稳定唯一的 key。
     * 不暴露、不带语义前缀——用户不可见,LazyColumn 只看唯一性。
     */
    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                val flow = when (_mode.value) {
                    RunMode.STREAM -> agent.runStream(text)
                    RunMode.BATCH -> agent.run(text)
                }
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
                _messages.update { it + UiMessage.User(event.userInput, id = nextUiId()) }
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
                val msg = UiMessage.ToolInProgress(event.callId, event.toolName)
                inProgressByCallId[event.callId] = msg
                _messages.update { it + msg }
            }
            is AgentEvent.ToolCallEnd -> {
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
                _messages.update { it + UiMessage.Assistant(event.result.message.content ?: "", id = nextUiId()) }
                _liveBubble.value = null
            }
            is AgentEvent.Failed -> {
                _messages.update {
                    it + UiMessage.Error(event.cause.message ?: "Unknown error", id = nextUiId())
                }
                _liveBubble.value = null
            }
            is AgentEvent.MemoryCompressing -> {
                // 等待压缩完成
            }
            is AgentEvent.MemoryCompressed -> {
                // 记忆压缩完成后，从 Memory 重新加载消息以保持同步
                memory?.let { mem ->
                    viewModelScope.launch { reloadMessages(mem) }
                }
            }
        }
    }
}

class ChatViewModelFactory(
    private val agentFactory: () -> Agent,
    private val memory: Memory? = null,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(agentFactory, memory) as T
    }
}
