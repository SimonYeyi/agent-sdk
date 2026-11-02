package io.github.yeyi.agent.demo.agent.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.demo.agent.demo.DemoAgentFactory
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

public data class SessionUiState(
    val sessions: List<Session> = emptyList(),
    val currentSession: Session? = null,
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val liveBubble: LiveBubble? = null,
    val pendingMessage: String? = null,
    val isNewSessionPending: Boolean = false,
    val isToolExecutionPending: Boolean = false,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = true,
    val isLoadingMore: Boolean = false
)

public class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val hookPipeline = HookPipeline(logging = true)
    private val sessionManager = SessionManager(application.filesDir, hookPipeline)
    private val demoAgent = AgentReference(name = "demo")
    private val _uiState = MutableStateFlow(SessionUiState())
    public val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val accountId = "test_user"

    // 会话场景 agent 语义上就是长驻的：与 ChatViewModel 的单实例对齐，
    // 不再逐条消息新建（每次新建会在架构 2 下遗留仍持有在途回合的私有 scope）。
    // 换会话 = 换 memory，故切走前必须 shutdown 作废旧实例。
    private var agent: io.github.yeyi.agent.Agent? = null

    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    init {
        loadSessions()
    }

    public fun loadSessions() {
        viewModelScope.launch {
            val sessions = sessionManager.list(accountId)
            _uiState.value = _uiState.value.copy(sessions = sessions)
        }
    }

    public fun createSession(name: String) {
        viewModelScope.launch {
            try {
                val session = sessionManager.create(accountId, name, demoAgent)
                loadSessions()
                selectSession(session.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    public fun createSessionAndSelect(name: String) {
        // 只是标记为待创建状态，不真正创建会话
        _uiState.value = _uiState.value.copy(
            currentSession = null,
            messages = emptyList(),
            inputText = "",
            isNewSessionPending = true
        )
    }

    public fun selectSession(sessionId: String) {
        viewModelScope.launch {
            try {
                // 切会话 = 换 memory：旧 agent 绑的是上一个会话的记忆，必须作废
                releaseAgent()
                val session = sessionManager.get(accountId, sessionId)
                val chatMessages = session.conversation.history(1)
                    .filter { entry ->
                        val msg = entry.message
                        msg is ChatMessage.User ||
                        (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank())
                    }
                    .map { it.toUiMessage() }
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    messages = chatMessages,
                    currentPage = 1,
                    hasMorePages = true,
                    isLoadingMore = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    public fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    public fun sendMessage() {
        val currentSession = _uiState.value.currentSession
        val inputText = _uiState.value.inputText.trim()
        if (inputText.isEmpty()) return

        if (_uiState.value.isNewSessionPending || currentSession == null) {
            // 真正的会话在发送时才创建，用第一个问题作为会话名
            _uiState.value = _uiState.value.copy(
                pendingMessage = inputText,
                inputText = "",
                isNewSessionPending = false
            )
            createSessionAndSend(inputText)
        } else {
            sendMessageInternal(currentSession, inputText)
        }
    }

    private fun createSessionAndSend(name: String) {
        viewModelScope.launch {
            try {
                val session = sessionManager.create(accountId, name, demoAgent)
                loadSessions()
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    messages = emptyList()
                )
                val pending = _uiState.value.pendingMessage
                if (pending != null) {
                    _uiState.value = _uiState.value.copy(pendingMessage = null)
                    sendMessageInternal(session, pending)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun sendMessageInternal(session: Session, inputText: String) {
        _uiState.value = _uiState.value.copy(
            inputText = "",
            isLoading = true
        )

        viewModelScope.launch {
            try {
                val agent = agent
                    ?: DemoAgentFactory.create(session.memory, hookPipeline).also { agent = it }
                // 会话 UI 依赖流式增量；Agent 未实现 Streamable 时降级为批式路径。
                val eventFlow = (agent as? Streamable)?.runStream(AgentQuery.text(inputText))
                    ?: agent.run(AgentQuery.text(inputText))
                eventFlow.collect { event ->
                    when (event) {
                        is io.github.yeyi.agent.AgentEvent.Initial -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.User(
                                    text = event.query.parts.text,
                                    id = nextUiId(),
                                ),
                                liveBubble = null,
                                isToolExecutionPending = false
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallExplanation -> {
                            event.text?.let { text ->
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages + UiMessage.Assistant(text, id = nextUiId()),
                                    liveBubble = null,
                                    isToolExecutionPending = true
                                )
                            }
                        }
                        is io.github.yeyi.agent.AgentEvent.TextDelta -> {
                            _uiState.value = _uiState.value.copy(
                                liveBubble = _uiState.value.liveBubble?.let {
                                    it.copy(text = it.text + event.text)
                                } ?: LiveBubble(nextUiId(), event.text),
                                isToolExecutionPending = false
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallStart -> {
                            // 信号，不做消息提交
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallEnd -> {
                            // Do nothing, wait for next text delta
                        }
                        is io.github.yeyi.agent.AgentEvent.Final -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.Assistant(event.result.message.content ?: "", id = nextUiId()),
                                liveBubble = null
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.Failed -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.Error(event.cause.message ?: "Unknown error", id = nextUiId()),
                                liveBubble = null,
                                isToolExecutionPending = false
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.Canceled -> {
                            _uiState.value = _uiState.value.copy(
                                error = "已打断",
                                liveBubble = null,
                                isToolExecutionPending = false
                            )
                        }
                        is AgentEvent.MemoryCompressing -> {
                            // 等待压缩完成
                        }
                        is AgentEvent.MemoryCompressed -> {
                            // 界面消息已不依赖 memory，无需额外处理
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    liveBubble = null
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /** 作废当前 agent：架构 2 下回合跑在 agent 私有 scope，不 shutdown 会留下无人观察的在途回合。 */
    private fun releaseAgent() {
        val old = agent
        agent = null
        (old as? io.github.yeyi.agent.Agent.Shutdownable)?.shutdown()
    }

    public fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionManager.delete(sessionManager.get(accountId, sessionId))
                if (_uiState.value.currentSession?.id == sessionId) {
                    // 当前会话被删 = memory 已作废：旧 agent 一并作废。
                    releaseAgent()
                    _uiState.value = _uiState.value.copy(
                        currentSession = null,
                        messages = emptyList()
                    )
                }
                loadSessions()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    public fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    public fun loadMoreMessages() {
        val session = _uiState.value.currentSession ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMorePages) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val nextPage = _uiState.value.currentPage + 1
                val olderMessages = session.conversation.history(nextPage)
                    .filter { entry ->
                        val msg = entry.message
                        msg is ChatMessage.User ||
                        (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank())
                    }
                    .map { it.toUiMessage() }

                if (olderMessages.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        hasMorePages = false,
                        isLoadingMore = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        messages = olderMessages + _uiState.value.messages,
                        currentPage = nextPage,
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoadingMore = false
                )
            }
        }
    }

    }

/**
 * 记忆条目 → UI 消息。
 *
 * 带 "error" label 的助手消息是运行失败写入的记忆，渲染为错误气泡，
 * 与运行时 [io.github.yeyi.agent.AgentEvent.Failed] 的气泡样式一致 ——
 * 重启后恢复渲染不把错误降级成普通助手气泡。
 */
private fun MemoryEntry.toUiMessage(): UiMessage {
    val msg = message
    if (msg is ChatMessage.Assistant && label == "error") {
        return UiMessage.Error(cause = msg.content ?: "Unknown error", id = UUID.randomUUID().toString())
    }
    return msg.toUiMessage()
}

private fun ChatMessage.toUiMessage(): UiMessage {
    return when (this) {
        is ChatMessage.User -> UiMessage.User(
                text = parts.text,
                id = UUID.randomUUID().toString(),
            )
        is ChatMessage.Assistant -> UiMessage.Assistant(text = content ?: "", id = UUID.randomUUID().toString())
        is ChatMessage.System -> UiMessage.Assistant(text = content, id = UUID.randomUUID().toString())
        is ChatMessage.ToolResult -> UiMessage.ToolExecution(
            callId = toolCallId,
            toolName = toolName,
            result = ToolExecutionResult(parts, isError)
        )
    }
}
