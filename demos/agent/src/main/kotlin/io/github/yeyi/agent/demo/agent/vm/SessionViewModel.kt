package io.github.yeyi.agent.demo.agent.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.demo.agent.runtime.AgentRuntime
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.tool.UiSink
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

public data class SessionUiState(
    val sessions: List<Session> = emptyList(),
    val currentSession: Session? = null,
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    /** 轮次进行中：发送即置位，终态事件替换 LoadingBubble 时复位。 */
    val isLoading: Boolean = false,
    val error: String? = null,
    val liveBubble: LiveBubble? = null,
    val pendingMessage: String? = null,
    val isNewSessionPending: Boolean = false,
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

    private var agentId: String = ""

    // 卡片旁路:sink 留在收集它的地方,agentId 与 sink 注册进全局路由表。
    private val sink = UiSink()

    /** 卡片按 toolCallId 锚点缓存：卡片先到时暂存，事件到达后按锚点补插归位。 */
    private val cardsByCallId = mutableMapOf<String, CardIr>()

    /** 已归位到消息列表的卡片锚点，保证卡片流与事件流竞态下插入幂等。 */
    private val insertedCards = mutableSetOf<String>()

    private val json = Json { ignoreUnknownKeys = true }

    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    init {
        loadSessions()
        // 卡片订阅:卡片经旁路到达,永不进记忆,只在这里落成 UI 消息。
        // 按 toolCallId 缓存后立即尝试锚点归位,不直接追加——保证卡片恒在工具结果之后；
        // 同时落盘持久化,重启后按锚点恢复。
        viewModelScope.launch {
            sink.cards.collect { card ->
                cardsByCallId[card.instanceId] = card
                insertCardAfterAnchor(card.instanceId)
                persistCard(card)
            }
        }
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
                // 卡片缓存绑定旧会话，切走即清，再全量读出新会话持久化的卡片供恢复归位
                loadPersistedCards(session)
                val chatMessages = restoreCards(
                    session.conversation.history(1)
                        .filter { entry ->
                            val msg = entry.message
                            msg is ChatMessage.User ||
                            (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank())
                        }
                        .map { it.toUiMessage() }
                )
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    messages = chatMessages,
                    currentPage = 1,
                    hasMorePages = true,
                    isLoadingMore = false,
                    isLoading = false
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
                // 新会话 = 换 memory：旧 agent 作废，后续发送按新会话记忆重建
                releaseAgent()
                // 卡片缓存绑定旧会话，换新会话即清
                cardsByCallId.clear()
                insertedCards.clear()
                _uiState.value = _uiState.value.copy(
                    currentSession = session,
                    messages = emptyList(),
                    isLoading = false
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
            // agent 构建失败发生在回合建立之前，没有事件通道可用，只能在此兜住。
            val agent = runCatching {
                agent ?: newAgent(session).also { agent = it }
            }.getOrElse { e ->
                _uiState.value = _uiState.value.copy(error = e.message, isLoading = false)
                return@launch
            }
            // 会话 UI 依赖流式增量；Agent 未实现 Streamable 时降级为批式路径。
            val eventFlow = (agent as? Streamable)?.runStream(AgentQuery.text(inputText))
                ?: agent.run(AgentQuery.text(inputText))
            eventFlow
                // 轮次收尾挂在流的完成上：终局事件后流完成、订阅被取消，两者都会执行。
                // isLoading 兜底复位，防止异常路径残留 LoadingBubble。
                .onCompletion {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .collect { event ->
                    when (event) {
                        is io.github.yeyi.agent.AgentEvent.Initial -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.User(
                                    text = event.query.parts.text,
                                    id = nextUiId(),
                                ),
                                liveBubble = null
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallExplanation -> {
                            event.text?.let { text ->
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages + UiMessage.Assistant(text, id = nextUiId()),
                                    liveBubble = null
                                )
                            }
                        }
                        is io.github.yeyi.agent.AgentEvent.TextDelta -> {
                            _uiState.value = _uiState.value.copy(
                                liveBubble = _uiState.value.liveBubble?.let {
                                    it.copy(text = it.text + event.text)
                                } ?: LiveBubble(nextUiId(), event.text)
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallStart -> {
                            // 信号，不提交消息：执行指示由 LoadingBubble（轮次进行中）统一呈现
                        }
                        is io.github.yeyi.agent.AgentEvent.ToolCallEnd -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.ToolExecution(
                                    callId = event.toolCall.id,
                                    toolName = event.toolCall.name,
                                    result = event.result,
                                )
                            )
                            // 结果消息已入列，卡片若先到达（暂存于 cardsByCallId）此时按结果锚点归位
                            insertCardAfterAnchor(event.toolCall.id)
                        }
                        is io.github.yeyi.agent.AgentEvent.Final -> {
                            val content = event.result.message.content
                            val messages = if (content.isNullOrBlank()) {
                                _uiState.value.messages
                            } else {
                                _uiState.value.messages + UiMessage.Assistant(content, id = nextUiId())
                            }
                            _uiState.value = _uiState.value.copy(
                                messages = messages,
                                liveBubble = null,
                                isLoading = false
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.Failed -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + UiMessage.Error(event.cause.message ?: "Unknown error", id = nextUiId()),
                                liveBubble = null,
                                isLoading = false
                            )
                        }
                        is io.github.yeyi.agent.AgentEvent.Canceled -> {
                            // Canceled 唯一来源是切/删会话的 releaseAgent——
                            // 用户已切走，不渲染打断横幅，只清回合中的临时 UI 状态。
                            _uiState.value = _uiState.value.copy(
                                liveBubble = null,
                                isLoading = false
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
        }
    }

    /**
     * 按锚点把卡片插入消息列表：找到对应 ToolExecution（工具结果）消息后插入其后。
     *
     * 产卡工具是 final 工具，无模型总结语，卡片即最终回复，因此必须落在工具结果之后，
     * 而非调用中气泡之后。
     *
     * 卡片流与事件流双向竞态均由 [insertedCards] 幂等收敛：
     * - 卡片先于事件到达：找不到锚点则暂存 [cardsByCallId]，事件到达后再次触发；
     * - 事件先于卡片到达：直接命中锚点插入；卡片重复到达时已入集合直接跳过。
     */
    private fun insertCardAfterAnchor(instanceId: String) {
        if (instanceId in insertedCards) return
        val card = cardsByCallId[instanceId] ?: return
        _uiState.update { state ->
            val anchorIndex = state.messages.indexOfFirst { msg ->
                msg is UiMessage.ToolExecution && msg.callId == instanceId
            }
            if (anchorIndex < 0) return@update state
            insertedCards.add(instanceId)
            val result = ArrayList(state.messages)
            result.add(anchorIndex + 1, UiMessage.Card(card, id = "card-$instanceId"))
            state.copy(messages = result)
        }
    }

    /**
     * 卡片落盘：emit 即持久化，重启后按锚点恢复。
     *
     * 写入会话私有目录内的 cards.jsonl（每行一张卡片 IR），随会话目录删除一并清理；
     * 同一 instanceId（= 工具调用 callId）只会发射一次，天然不重复追加。
     */
    private fun persistCard(card: CardIr) {
        val session = _uiState.value.currentSession ?: return
        val file = File(sessionManager.sessionRoot(accountId, session.id), CARDS_FILE)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file.appendText(json.encodeToString(card) + "\n")
            }
        }
    }

    /** 全量读出会话持久化的卡片，填充 [cardsByCallId]，供恢复路径按锚点归位。 */
    private suspend fun loadPersistedCards(session: Session) {
        val cards = withContext(Dispatchers.IO) {
            val file = File(sessionManager.sessionRoot(accountId, session.id), CARDS_FILE)
            if (!file.exists()) return@withContext emptyList<CardIr>()
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString<CardIr>(it) }.getOrNull() }
                .distinctBy { it.instanceId }
        }
        cardsByCallId.clear()
        cardsByCallId.putAll(cards.associateBy { it.instanceId })
        insertedCards.clear()
    }

    /**
     * 恢复归位：遍历纯 memory 消息列表，遇 ToolExecution 即把缓存中对应卡片插入其后。
     *
     * 仅用于恢复路径（selectSession 首页 / loadMoreMessages 更早页，消息列表均为纯消息
     * 不含卡片）；实时路径走 [insertCardAfterAnchor]，二者由 [insertedCards] 幂等衔接。
     */
    private fun restoreCards(messages: List<UiMessage>): List<UiMessage> {
        val restored = ArrayList<UiMessage>(messages.size)
        for (msg in messages) {
            restored.add(msg)
            if (msg is UiMessage.ToolExecution) {
                cardsByCallId[msg.callId]?.let { card ->
                    restored.add(UiMessage.Card(card, id = "card-${card.instanceId}"))
                    insertedCards.add(card.instanceId)
                }
            }
        }
        return restored
    }

    /** 新 agent:以 session.id 作为身份标识并登记到卡片路由表（会话与 agent 一一对应）。 */
    private fun newAgent(session: Session): io.github.yeyi.agent.Agent {
        val id = session.id
        agentId = id
        AgentRuntime.sinkRegistry.register(id, sink)
        return AgentRuntime.buildAgent(agentId, session.memory)
    }

    /** 作废当前 agent：架构 2 下回合跑在 agent 私有 scope，不 shutdown 会留下无人观察的在途回合。 */
    private fun releaseAgent() {
        val old = agent
        agent = null
        // 旧 agent 的 id 不再指向任何 agent,解注册避免路由表留下死条目
        AgentRuntime.sinkRegistry.unregister(agentId)
        (old as? io.github.yeyi.agent.Agent.Shutdownable)?.shutdown()
    }

    /**
     * 作废当前 agent（ViewModel 终结时的唯一清理动作）。
     *
     * 触发路径有二：组合销毁（返回 chat）时由 Screen 的 DisposableEffect 显式调用；
     * Activity finish 时由框架在 ViewModelStore 清理时调用。两条路径都保证
     * 在途回合以 [io.github.yeyi.agent.AgentEvent.Canceled] 终局收尾，订阅随之断开。
     */
    public override fun onCleared() {
        releaseAgent()
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
                        messages = emptyList(),
                        isLoading = false
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
                // 更早页恢复同样走锚点归位：页内 ToolExecution 后补插持久化的卡片
                val olderMessages = restoreCards(
                    session.conversation.history(nextPage)
                        .filter { entry ->
                            val msg = entry.message
                            msg is ChatMessage.User ||
                            (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank())
                        }
                        .map { it.toUiMessage() }
                )

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

    private companion object {
        /** 会话私有目录内卡片持久化文件名（每行一张卡片 IR，随会话目录删除清理）。 */
        const val CARDS_FILE = "cards.jsonl"
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
