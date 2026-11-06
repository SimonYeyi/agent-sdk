package io.github.yeyi.agent.demo.agent.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.demo.agent.runtime.AgentRuntime
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.tool.UiSink
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RunMode { STREAM, BATCH }

data class LiveBubble(val id: String, val text: String)

class ChatViewModel(
    agentFactory: ((Memory, String) -> Agent)? = null,
) : ViewModel() {

    // 卡片旁路:sink 留在收集它的地方,agentId 与 sink 注册进全局路由表。
    private val sink = UiSink()

    private val agentFactory: (Memory, String) -> Agent =
        agentFactory ?: { memory, agentId ->
            AgentRuntime.buildAgent(agentId = agentId, memory = memory)
        }

    private val _mode = MutableStateFlow(RunMode.STREAM)
    val mode: StateFlow<RunMode> = _mode.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _liveBubble = MutableStateFlow<LiveBubble?>(null)
    val liveBubble: StateFlow<LiveBubble?> = _liveBubble.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val inProgressByCallId = mutableMapOf<String, UiMessage.ToolInProgress>()

    /** 卡片按 toolCallId 锚点缓存：内存压缩/会话恢复后仍可按锚点归位。 */
    private val cardsByCallId = mutableMapOf<String, CardIr>()

    /** 已归位到消息列表的卡片锚点，保证卡片流与事件流竞态下插入幂等。 */
    private val insertedCards = mutableSetOf<String>()

    private var memory: Memory = io.github.yeyi.agent.memory.InMemoryMemory()

    /** agent 身份标识:卡片旁路的路由键,每个 agent 实例一个。 */
    private var agentId: String = ""
    private var agentSeq: Int = 0
    private var agent: Agent = createAgent()

    private var runJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { reloadMessages() }
        // 卡片旁路流订阅：实时广播，卡片按 toolCallId 插入对应工具调用消息之后
        viewModelScope.launch {
            sink.cards.collect { card ->
                cardsByCallId[card.instanceId] = card
                insertCardAfterAnchor(card.instanceId)
            }
        }
    }

    /** 渲染后端：UI 层经 [UiRenderAdapter.RenderCard] 渲染卡片，demo 全局共用 AgentRuntime 装配。 */
    val cardAdapter: UiRenderAdapter get() = AgentRuntime.cardAdapter

    /** 新 agent 身份标识:VM 内单调递增,重建 agent 时换新 id。 */
    private fun newAgentId(): String = "demo-agent-${++agentSeq}"

    /** 建 agent 并把它的 id 登记到卡片路由表。 */
    private fun createAgent(): Agent {
        val id = newAgentId()
        agentId = id
        AgentRuntime.sinkRegistry.register(id, sink)
        return agentFactory(memory, id)
    }

    private suspend fun reloadMessages() {
        val history = memory.history()
        val base = history
            .filter { entry ->
                val msg = entry.message
                msg is ChatMessage.User ||
                        (msg is ChatMessage.Assistant && !msg.content.isNullOrBlank()) ||
                        (msg is ChatMessage.System && !msg.content.isNullOrBlank())
            }
            .map { it.toUiMessage() }
        // 锚点归位：本地自持的卡片按 toolCallId 插入对应 ToolResult 之后，恢复后不丢卡
        val restored = ArrayList<UiMessage>(base.size)
        insertedCards.clear()
        for (msg in base) {
            restored.add(msg)
            if (msg is UiMessage.ToolExecution) {
                cardsByCallId[msg.callId]?.let { card ->
                    restored.add(UiMessage.Card(card, id = "card-${card.instanceId}"))
                    insertedCards.add(card.instanceId)
                }
            }
        }
        _messages.value = restored
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
        // 订阅先掐断再 shutdown —— shutdown 后迟到的 Canceled 回放不再渲染。
        val oldAgent = agent
        runJob?.cancel()
        runJob = null
        _messages.value = emptyList()
        _liveBubble.value = null
        inProgressByCallId.clear()
        cardsByCallId.clear()
        insertedCards.clear()
        memory = io.github.yeyi.agent.memory.InMemoryMemory()
        // 旧 agent 的 id 不再指向任何 agent,解注册避免路由表留下死条目
        AgentRuntime.sinkRegistry.unregister(agentId)
        // 换 agent 在同一主线程回调里完成，run() 读不到「旧 agent 已 shutdown、
        // 字段尚未替换」的中间态——并发 run 的 ISE 拒绝由此不可达。
        agent = createAgent()
        (oldAgent as? Agent.Shutdownable)?.shutdown()
    }

    /**
     * 卡片动作回流桥：渲染层命中白名单动作时，把 [ActionEvent] 组装为新一轮推理。
     *
     * 回流指令以 [io.github.yeyi.agent.genui.ir.Action.label] 作为模型可读文案——
     * 动作集合由工具作者声明，label 供模型结合卡片与历史理解意图，无需专用意图字段。
     */
    fun onCardAction(event: ActionEvent) {
        if (_isProcessing.value) return
        sendUserInput(event.action.label)
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
        _messages.update { list ->
            val anchorIndex = list.indexOfFirst { msg ->
                msg is UiMessage.ToolExecution && msg.callId == instanceId
            }
            if (anchorIndex < 0) return@update list
            insertedCards.add(instanceId)
            val result = ArrayList(list)
            result.add(anchorIndex + 1, UiMessage.Card(card, id = "card-$instanceId"))
            result
        }
    }

    /**
     * VM 内部单调计数器,为 LazyColumn 生成稳定唯一的 key。
     * 不暴露、不带语义前缀——用户不可见,LazyColumn 只看唯一性。
     */
    private var nextUiId: Int = 0
    private fun nextUiId(): String = (++nextUiId).toString()

    fun sendUserInput(text: String) {
        if (text.isBlank() || _isProcessing.value) return

        // run() 调用即启动回合（架构 2）——在点击回调里同步启动，
        // 而不是等 viewModelScope 的协程被调度：清空/关闭页面若抢在协程开跑前
        // 引用旧 agent，这一轮就成了无人观察的在途回合。
        // 流式是可选能力：Agent 未实现 Streamable 时降级为批式路径。
        val flow = when (_mode.value) {
            RunMode.STREAM -> (agent as? Streamable)?.runStream(AgentQuery.text(text))
                ?: agent.run(AgentQuery.text(text))

            RunMode.BATCH -> agent.run(AgentQuery.text(text))
        }
        _isProcessing.value = true

        runJob = viewModelScope.launch {
            // 回合内一切失败/取消都是领域事件，collect 不抛。轮次收尾挂在流的完成上：
            // 终局事件后流完成、订阅被取消（clearMessages），onCompletion 都会执行——
            // 后者收不到终局事件，靠它归位 isProcessing（不归位 VM 永久拒绝新输入）。
            flow.onCompletion {
                _liveBubble.value = null
                inProgressByCallId.clear()
                _isProcessing.value = false
            }.collect { handleEvent(it) }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Initial -> {
                _messages.update {
                    it + UiMessage.User(
                        text = event.query.parts.text,
                        id = nextUiId(),
                    )
                }
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
                // 卡片可能先于事件到达（暂存于 cardsByCallId），事件到位后补插入
                insertCardAfterAnchor(event.toolCall.id)
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
                // 结果消息已入列，卡片若先到达（暂存于 cardsByCallId）此时按结果锚点归位
                insertCardAfterAnchor(event.toolCall.id)
            }

            is AgentEvent.Final -> {
                val content = event.result.message.content
                if (!content.isNullOrBlank()) {
                    _messages.update {
                        it + UiMessage.Assistant(content, id = nextUiId())
                    }
                }
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

    /**
     * 页面销毁（组合退出 / Activity finish）：回收卡片路由与在途回合。
     *
     * 此时 viewModelScope 已随之取消（sink 订阅已断），shutdown 迟到的
     * Canceled 回放不会渲染；不 shutdown 会留下无人观察的在途回合，
     * 不反注册会让路由表留下死条目（agentId 每次重建递增累积）。
     */
    override fun onCleared() {
        AgentRuntime.sinkRegistry.unregister(agentId)
        (agent as? Agent.Shutdownable)?.shutdown()
    }
}
