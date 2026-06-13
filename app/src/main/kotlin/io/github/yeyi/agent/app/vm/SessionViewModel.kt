package io.github.yeyi.agent.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.app.demo.DemoAgentFactory
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

public data class SessionUiState(
    val sessions: List<Session> = emptyList(),
    val currentSession: Session? = null,
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val liveBubble: LiveBubble? = null
)

public class SessionViewModel(
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    public val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val userId = "test_user"

    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    init {
        loadSessions()
    }

    public fun loadSessions() {
        viewModelScope.launch {
            val sessions = sessionManager.list(userId)
            _uiState.value = _uiState.value.copy(sessions = sessions)
        }
    }

    public fun createSession(name: String) {
        viewModelScope.launch {
            try {
                val session = sessionManager.create(userId, name)
                loadSessions()
                selectSession(session.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    public fun selectSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val session = sessionManager.get(userId, sessionId)
                val messages = session.memory.history()
                    .filter { msg ->
                        msg is ChatMessage.User ||
                        (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank())
                    }
                    .map { it.toUiMessage() }
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    messages = messages
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
        val currentSession = _uiState.value.currentSession ?: return
        val inputText = _uiState.value.inputText.trim()
        if (inputText.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            inputText = "",
            isLoading = true
        )

        viewModelScope.launch {
            try {
                val agent = DemoAgentFactory.create(currentSession.memory)
                agent.runStream(inputText).collect { event ->
                    when (event) {
                        is io.github.yeyi.agent.AgentEvent.Initial -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.User(event.userInput, id = nextUiId()),
                                liveBubble = null
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.Reasoning -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.Assistant(event.text, id = nextUiId()),
                                liveBubble = null
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.TextDelta -> {
                            _uiState.value = _uiState.value.copy(
                                liveBubble = _uiState.value.liveBubble?.let {
                                    it.copy(text = it.text + event.text)
                                } ?: LiveBubble(nextUiId(), event.text)
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallStart -> {
                            val live = _uiState.value.liveBubble
                            if (live != null) {
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages + UiMessage.Assistant(live.text, id = live.id),
                                    liveBubble = null
                                )
                            }
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
                                liveBubble = null
                            )
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

    public fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionManager.delete(userId, sessionId)
                if (_uiState.value.currentSession?.id == sessionId) {
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

    public companion object {
        public fun create(sessionDir: File): SessionViewModel {
            return SessionViewModel(SessionManager(sessionDir))
        }
    }
}

private fun ChatMessage.toUiMessage(): UiMessage {
    return when (this) {
        is ChatMessage.User -> UiMessage.User(content, id = UUID.randomUUID().toString())
        is ChatMessage.Assistant -> UiMessage.Assistant(text = content ?: "", id = UUID.randomUUID().toString())
        is ChatMessage.System -> UiMessage.Assistant(text = content, id = UUID.randomUUID().toString())
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