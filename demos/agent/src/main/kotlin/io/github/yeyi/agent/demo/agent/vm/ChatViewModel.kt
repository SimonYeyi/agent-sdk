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

    /**
     * 在途指令的瞬时提示 —— 签收（指令已投递进本轮信箱）时置起，
     * 入卷（指令进了本轮对话）或轮次收尾时归位。
     */
    private val _steerHint = MutableStateFlow<String?>(null)
    val steerHint: StateFlow<String?> = _steerHint.asStateFlow()

    /**
     * 回合进行中的状态：由事件流如实反映——[AgentEvent.Initial] 置起，
     * 轮次收尾（流的完成）归位。
     */
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
        val oldAgent = agent
        _isProcessing.value = false
        _messages.value = emptyList()
        _liveBubble.value = null
        _steerHint.value = null
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
        submit(event.action.label)
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

    /**
     * 提交输入 —— 有活跃轮次就注入那一轮，没有就开新一轮。
     *
     * 按钮形态（Steer / Send）只是提示，真正的分派由 agent 的槽位决定：
     * 先试 [Agent.Steerable.steer]，投递不到（没有活跃轮次）就直接开新轮 ——
     * 合成一条路，调用方不必先判断状态再选路：判断用的那个状态可能已经过期
     * （轮次刚收尾的那一帧，按钮显示与实际状态对不上）。
     *
     * steer 投递不到即意味着槽位已还（core 的契约：先还槽、后发终局事件），
     * 所以这里的 run 不会撞上并发轮次。
     *
     * steer 返回 true 只说明指令被签收（进了本轮信箱，还没进对话）—— 给一条
     * 瞬时提示，免得输入框清空后用户不知指令去哪儿了；指令入卷时（事件流
     * 告知）提示归位、用户气泡出现。
     */
    fun submit(text: String) {
        if (text.isBlank()) return
        val steerable = agent as? Agent.Steerable
        if (steerable != null && steerable.steer(AgentQuery.text(text))) {
            _steerHint.value = "已注入当前轮次，模型将在下一轮采纳"
            return
        }
        runTurn(text)
    }

    /**
     * 跑一轮：调用即启动回合（架构 2），流式是可选能力——
     * Agent 未实现 Streamable 时降级为批式路径。
     */
    private fun runTurn(text: String) {
        val current = agent
        val flow = when (_mode.value) {
            RunMode.STREAM -> (agent as? Streamable)?.runStream(AgentQuery.text(text))
                ?: agent.run(AgentQuery.text(text))

            RunMode.BATCH -> agent.run(AgentQuery.text(text))
        }
        viewModelScope.launch {
            try {
                flow.collect { event ->
                    // 会话已被 clearMessages 换掉：旧轮的事件不再落进新会话。
                    if (current !== agent) return@collect
                    handleEvent(event)
                }
            } finally {
                // 轮次收尾：终局事件已渲染，进行中状态归位。
                // 会话已被 clearMessages 换掉时不得归位 —— 旧轮的收尾落在新会话的
                // 状态上（进行中标记被抹、流式气泡被清、工具标记被清）。
                if (current === agent) {
                    _isProcessing.value = false
                    _liveBubble.value = null
                    // 轮次结束：在途指令若没等到消费就随轮作废，提示一并归位。
                    _steerHint.value = null
                    inProgressByCallId.clear()
                }
            }
        }
    }

    /**
     * 打断在途轮次 —— agent 以 [AgentEvent.Canceled] 终局收尾，agent 本身仍可再用。
     *
     * 直接走 [Agent.Cancelable.cancel]：只投递取消，不等待。core 的终局顺序是
     * "先还槽、后发终局事件"，故收集方收到 Canceled 时槽位已还 —— 下一轮直接开即可。
     */
    fun cancel() {
        (agent as? Agent.Cancelable)?.cancel("user canceled")
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
                // 回合开始的标志：进行中状态由事件流如实反映。
                _isProcessing.value = true
                _liveBubble.value = null
            }

            is AgentEvent.Steering -> {
                // 在途指令已入卷（进了本轮对话，随下一次模型请求生效）：
                // 像普通用户消息一样入列 —— 渲染时刻与记忆里的事实一致，
                // 用户看得见自己注入的指令（否则输入框清空后消息就"消失"了）。
                _messages.update {
                    it + UiMessage.User(
                        text = event.query.parts.text,
                        id = nextUiId(),
                    )
                }
                // 签收时的提示到这儿归位：指令已经进了对话。
                _steerHint.value = null
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
                // 终局三选一里的第三种：回合被取消（用户打断 / 清空消息 / 关闭页面）——
                // 不是运行失败，不渲染气泡；进行中状态由轮次收尾归位。
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
