package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.log.log
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.memory.RepairedMemory
import io.github.yeyi.agent.memory.RoundsBoundedMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.memory.addToMemory
import io.github.yeyi.agent.memory.toChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.tool.AsyncTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.atomic.AtomicReference

public class ReActAgent internal constructor(
    private val persona: AgentPersona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent, Agent.Streamable, Agent.Steerable, Agent.Cancelable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)

    /**
     * 当前活跃轮次 —— 槽位按定义属于持有它的 agent。
     *
     * 轮次身份（[AgentTurn]）是槽位的**内容**：槽位里有轮次，轮次不持有槽位。
     */
    private val activeTurn = AtomicReference<AgentTurn?>(null)

    override fun run(query: AgentQuery): Flow<AgentEvent> {
        val turn = AgentTurn(query) { req -> llmProvider.chat(req) }
        return runTurn(turn)
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> {
        val streaming = StreamingRunner(llmProvider)
        lateinit var turn: AgentTurn
        turn = AgentTurn(query) { req ->
            streaming.run(req) { turn.flow.emit(AgentEvent.TextDelta(it)) }
        }
        return runTurn(turn)
    }

    /**
     * 打断当前回合 —— 只终止 agent 自身的执行，收集方协程不受影响。
     *
     * 回合从未被收集时（run() 已返回、流被放弃）：没有执行体可打断，而槽位也无人回收
     * —— agent 会被永久锁死，之后每次 run() 都抛 "Concurrent run not supported"。
     * 此时 cancel() 作废该轮（置已取消标志 + 关信箱）、把槽位还回去；该轮之后若被收集，
     * 以取消异常收尾（与运行中被打断同型）。
     *
     * @return 该轮收尾的句柄 —— 完成即代表槽位已还、信箱已关，之后可安全地再次 run()；
     *   无活跃轮次时返回 null（幂等 no-op）。调用方可以 join 它等旧轮退场，
     *   也可以不等待 —— 不等待时收尾完成前再次 run() 仍可能撞上并发拒绝。
     *   句柄只用于"等"：不要 cancel 它（[Agent.Cancelable.cancel] 的 KDoc 有说明）。
     */
    override fun cancel(): Job? {
        val turn = activeTurn.get() ?: return null // 无活跃轮次 → no-op（幂等）
        // true  ⟺ 该轮已被收集 ⟹ 打断已投递，槽位由该轮流的 onCompletion 认人归还。
        // false ⟺ 该轮从未被收集 ⟹ 没有 onCompletion 会来，槽位必须在此归还。
        if (turn.flow.cancel()) return turn.terminated
        // 从未被收集的轮次：当场退场（还槽 + 关信箱 + 收尾信号）。
        // 该轮从未被收集 ⇒ 没有执行体 ⇒ 退场不必取闸。
        retireTurn(turn)
        return turn.terminated
    }

    /**
     * [AgentTurn.lock] 序列化投递与终局 isEmpty 裁决+终态发射+关门，堵死
     * "add 返回 true 但消息困死 buffer 无人消费"的窗口。
     *
     * 完结束契约：返回 false ⟺ 无活跃 run ⟺ 终态事件(Final)已发射完毕。
     * 终局持闸期间阻塞等待，正是语义正确的"等待裁决"——既不能 true
     * （消息无人消费）也不能 false（Final 未发完，此时开新 run 会与旧 run 的事件混流）。
     *
     * 纪律：闸内允许且仅允许 onRunCompleted 与 emit(Final) 两个已知挂起点，
     * 严禁其他 suspend——持闸挂起会阻塞其他线程的 add，编译器不拦，只能靠约定。
     * 死锁硬约束：collector / AgentHook 回调内严禁同步调用 steer()（闸门不可重入）。
     */
    override fun steer(query: AgentQuery): Boolean {
        // 读槽位与投递之间轮次可能刚好终结（信箱已关）→ 投递失败时重读槽位再试；
        // 只有确认「此刻无活跃轮次」才返回 false —— 契约：false ⟹ 调用方可安全开新轮。
        // 终止性：trySend 只在信箱已关时失败，而信箱已关的轮次必定已不在槽位里 → 每轮迭代必然推进。
        while (true) {
            val turn = activeTurn.get() ?: return false
            // 投递在闸内：与终局裁决互斥 —— 投递若挤进"判空 → 发 Final → 关信箱"那个窗口，
            // 指令会既被接受又没被消费（steer 返回 true，指令却被 Final 带走）。
            turn.lock()
            val delivered = try {
                turn.steering.add(query)
            } finally {
                turn.unlock()
            }
            // 投递失败 = 信箱已关 = 那一轮已退场 —— 此刻可能已有新一轮占着槽位，
            // 故不能就此返回 false（false 的契约是"无活跃轮次，可安全开新轮"）；
            // 重读槽位再试，指令才能落到新一轮上。
            if (delivered) return true
        }
    }

    /**
     * 回合的公共骨架：占活跃槽位 → 交由可中断容器执行 → 无论何种终态都释放槽位。
     */
    private fun runTurn(turn: AgentTurn): Flow<AgentEvent> {
        // eager 占据活跃 run 槽位：调用即建轮，杜绝 "run 返回但未收集" 间隙里
        // steer() 误判空闲导致并发双 run。已有活跃 run 时抛并发异常。
        if (!activeTurn.compareAndSet(null, turn))
            error("Concurrent run not supported: another run is active")
        return turn.flow.start { loop(turn) }.onCompletion { cause ->
            // 非拥有者的收集（重复收集）不得收尾：认人 CAS 会命中仍在运行的轮次，
            // 把它的槽位提前释放（可开出并发双跑），并关掉它的信箱（steer() 对活着的轮返回 false）。
            if (cause is RepeatedCollectionException) return@onCompletion
            // 轮次退场：流已结束，没有执行体在跑 —— 退场不必取闸。
            retireTurn(turn)
        }
    }

    /**
     * 轮次退场 —— 还槽（认人）+ 关信箱 + 完成收尾信号。
     *
     * **不必在闸内**：闸护的是"终局裁决"与"投递"互斥，而退场时执行体已结束，
     * 唯一可能并发的只有投递 —— 两步之间被挤进一个投递，代价只是那轮 steer
     * 对已死的轮返回 true（与"cancel 之后 steer 仍可能返回 true"同一语义）。
     * 终局路径（[finalize]）顺手在闸内调它（它本就持闸跨越终态发射），
     * 那里的投递会被挡在闸外 → 对已死的轮返回 false。
     *
     * 先还槽、后关信箱：恒有「信箱已关 ⟹ 槽位已空」——
     * steer() 的重试循环靠这条必然推进，也保证「false ⟹ 可安全开新轮」。
     *
     * 关的是信箱而不是 turn.close()：终局时执行体正是在跑的那个自身，打断自己会
     * 把成功轮次变成取消轮次。
     */
    private fun retireTurn(turn: AgentTurn) {
        activeTurn.compareAndSet(turn, null) // 还槽（认人）：槽位若已是新一轮则空操作
        turn.steering.close()                   // 关信箱（幂等）
        turn.terminated.complete(Unit)           // 收尾信号：等待者醒来即可安全开新轮
    }

    private suspend fun loop(turn: AgentTurn) {
        try {
            // 本轮上下文：循环外的第 0 次 —— 第一次迭代之前就得有一个可读的。
            // 记忆压缩钩子挂在轮次开头，紧随其后的 query 归档写入就可能触发压缩
            // （老对话已超 maxRounds 时是常事），钩子要拿 context；
            // 失败路径同理 —— 迭代之前就失败时 onRunFailed 也要有。
            // 循环内每轮迭代由 loopOnce 刷新成当轮的上下文。
            turn.iterationContext = buildContext(turn.iterations)

            turn.flow.emit(AgentEvent.Initial(turn.query))

            memory.attachHook(
                MemoryCompressProxyHook(hook) { turn.flow.emit(it) },
                turn.iterationContext
            )
            modalityAdapter.archive(ChatMessage.User(turn.query.parts))
                .addToMemory(memory, turn.query.label)

            while (turn.iterations < maxIterations) {
                turn.iterations++
                loopOnce(turn)?.let { return }
            }

            throw AgentException.MaxIterations(maxIterations)
        } catch (cause: Throwable) {
            if (cause is CancellationException) {
                withContext(NonCancellable) {
                    RepairedMemory.repairOrphans(memory, RepairedMemory.CANCELLED)
                }
                throw cause
            }
            // 错误伪装成 Assistant 消息写入记忆：错误发生在助手侧执行链路，模型第二轮
            // 看到的是「我上一轮输出了错误」，可据此重试/澄清；UI 按 "error" label 渲染错误气泡。
            ChatMessage.Assistant(cause.message ?: cause.toString()).addToMemory(memory, "error")
            // 失败路径携带原始 Throwable,不再包成 AgentException —— 让上层自由判型。
            hook.safeInvoke { onRunFailed(turn.iterationContext, cause) }
            turn.flow.emit(AgentEvent.Failed(cause))
        }
    }

    private suspend fun loopOnce(turn: AgentTurn): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        if (!turn.steering.isEmpty()) consumeSteering(turn)

        val context = buildContext(turn.iterations).also {
            // 上下文挂到轮次上：一轮迭代一份，迭代内的钩子点共用同一个对象 ——
            // AgentContext.metadata 是可变 map，拆开就传不了数据了。
            turn.iterationContext = it
        }

        val request = buildRequest()

        val finalRequest = hook.safeInvoke { beforeLlmCall(context, request) } ?: request
        val response = llmCallWithContextOverflowHandle(finalRequest, turn)
        hook.safeInvoke { afterLlmResponse(context, response) }

        response.message.addToMemory(memory)

        if (response.message.toolCalls.isEmpty()) {
            // 正常终结契约：非短路路径的 Final 必须携带非空正文。LLM 既无工具调用又无正文
            // 属于异常输出，直接 fail-fast；content 为 null 的 Final 事件在整个库中仅由
            // 下方 allAsync 短路路径构造，外部消费者见到 null 即可断定来自异步工具终结。
            // 这是内部不变式而非调用方参数问题，故用 check 抛 IllegalStateException。
            check(response.message.content.isNullOrBlank().not()) {
                "Final event must carry non-blank content: LLM returned a response with neither tool calls nor content."
            }
            return finalize(turn, response)
        }

        val allToolAsync = response.message.toolCalls
            .all { call -> toolRegistry.getOrNull(call.name) is AsyncTool }

        callTools(turn, response, allToolAsync)

        if (allToolAsync) {
            val emptyResponse = ChatResponse(ChatMessage.Assistant(), null, FinishReason.Stop)
            emptyResponse.message.addToMemory(memory)
            return finalize(turn, emptyResponse)
        }

        return null
    }

    private suspend fun callTools(turn: AgentTurn, response: ChatResponse, allAsync: Boolean) {
        turn.flow.emit(
            AgentEvent.ToolCallExplanation(
                text = response.message.content?.takeIf { it != "" },
                toolCalls = response.message.toolCalls,
                allAsync = allAsync,
            )
        )

        for (call in response.message.toolCalls) {
            turn.flow.emit(AgentEvent.ToolCallStart(call))
            val synthetic = hook.safeInvoke { beforeToolCall(turn.iterationContext, call) }
            val startMs = System.currentTimeMillis()
            val raw = synthetic?.copy(isError = true) ?: toolRegistry.dispatch(
                call.name,
                call.arguments,
                ToolExecutionContext(call.id, turn.iterationContext)
            )
            val durMs = System.currentTimeMillis() - startMs
            val final = hook.safeInvoke {
                afterToolCall(turn.iterationContext, call, raw, synthetic != null, durMs)
            } ?: raw

            turn.toolCalls += AgentResult.ToolCallRecord(
                callId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                result = final,
                timestamp = java.time.Instant.now(),
            )

            modalityAdapter.archive(
                ChatMessage.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    parts = final.parts,
                    isError = final.isError,
                )
            ).addToMemory(memory)

            turn.flow.emit(AgentEvent.ToolCallEnd(call, final))
        }
    }

    /**
     * 终局协议：steer 检查点 → onRunCompleted → Final → destroy。
     * 正常收敛与 [AsyncTool] 短路共用，保证协议一致性。
     * 若在途指令 (steer) 非空，放弃终局返回 null，强制再跑一轮让 LLM 带着指令重新作答。
     */
    private suspend fun finalize(turn: AgentTurn, response: ChatResponse): AgentResult? {
        // 终局：Final 抢占 + 终局。与 steer() 闸门下互斥。
        turn.lock()
        if (!turn.steering.isEmpty()) {
            turn.unlock()
            // 指令已注入 memory，抢占 Final，强制再跑一轮让 LLM 带着新指令重新作答
            consumeSteering(turn)
            return null
        }
        val result = AgentResult(
            message = response.message,
            iterations = turn.iterations,
            toolCalls = turn.toolCalls.toList(),
            usage = response.usage,
        )
        hook.safeInvoke { onRunCompleted(turn.iterationContext, result) }
        try {
            turn.flow.emit(AgentEvent.Final(result))
            // 终局：终态发射 → 还槽 + 关门，全程持闸。
            // 闸门释放之后 steer() 拿到的 false 才意味着"可安全开新 run"——
            // 若把 emit(Final) 放闸外，缝隙里 steer()==false 触发的 fallback run
            // 会先发 Initial，旧 run 的 Final 后到，混流。
            retireTurn(turn)   // 还槽 + 关信箱 + 收尾信号（闸内核心，与退场路径同一件事）
        } finally {
            // 闸门用 Semaphore: release 可由任意线程执行 —— emit 是挂起点,
            // 协程恢复后可能换线程, 线程亲和锁在此 unlock 会抛 IllegalMonitorStateException。
            turn.unlock()
        }
        return result
    }

    private suspend fun consumeSteering(turn: AgentTurn) {
        turn.steering.drain().forEach { query ->
            // steer 注入的指令默认标 "steering"；调用方显式带 label 时优先采用
            modalityAdapter.archive(ChatMessage.User(query.parts))
                .addToMemory(memory, label = query.label ?: "steering")
        }
    }

    private suspend fun llmCallWithContextOverflowHandle(
        request: ChatRequest,
        turn: AgentTurn
    ): ChatResponse {
        return try {
            turn.llmCall(request)
        } catch (e: Throwable) {
            e.takeIf { it.isContextOverflow() }
                ?.let { log.warn(it) }
                ?.let { memory.handleContextOverflow() }
                ?.let { llmCallWithContextOverflowHandle(buildRequest(), turn) }
                ?: throw e
        }
    }

    private suspend fun buildRequest(): ChatRequest {
        val history = memory.history().map { it.toChatMessage() }
        val messages = modalityAdapter.resolve(history)
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(messages)
            },
            tools = toolRegistry.all().map(Tool::toDefinition)
        )
    }

    private fun buildContext(currentIteration: Int) = AgentContext(
        persona = persona,
        maxIterations = maxIterations,
        currentIteration = currentIteration,
        memory = ReadOnlyMemory(memory),
        llmProvider = llmProvider,
        tools = toolRegistry.all(),
        maxRounds = maxRounds,
    )

    private class MemoryCompressProxyHook(
        private val hook: AgentHook,
        private val emit: suspend (AgentEvent) -> Unit
    ) : AgentHook by hook {

        override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) {
            hook.safeInvoke { beforeMemoryCompress(context, summaries) }
            emit(AgentEvent.MemoryCompressing(summaries))
        }

        override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) {
            hook.safeInvoke { afterMemoryCompress(context, summaries) }
            emit(AgentEvent.MemoryCompressed(summaries))
        }
    }

    private class StreamingRunner(private val llmProvider: LlmProvider) {
        suspend fun run(request: ChatRequest, onDelta: suspend (String) -> Unit): ChatResponse {
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
            var finishReason: FinishReason? = null
            var usage: Usage? = null

            llmProvider.chatStream(request).collect { event ->
                when (event) {
                    is ChatResponseEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        onDelta(event.text)
                    }

                    is ChatResponseEvent.ToolCallDelta -> {
                        if (callNames[event.id] == null) {
                            callOrder.add(event.id)
                            // 首个 delta 必须携带 name 标记开始(契约);缺失属 provider 违约,显式失败而非静默空串
                            callNames[event.id] = requireNotNull(event.name) {
                                "First ToolCallDelta for call '${event.id}' must carry a non-null name to mark the start"
                            }
                        }
                        argumentsBuffers.getOrPut(event.id) { StringBuilder() }
                            .append(event.argumentsDelta)
                    }

                    is ChatResponseEvent.Done -> {
                        finishReason = event.finishReason
                        usage = event.usage
                    }

                    is ChatResponseEvent.Error -> throw event.cause
                }
            }

            val toolCalls: List<ToolCall> = callOrder.map { id ->
                val arguments = argumentsBuffers[id]?.toString()
                    ?.let { Json.parseToJsonElement(it) }
                    ?: JsonNull
                ToolCall(id = id, name = callNames[id]!!, arguments = arguments)
            }
            return ChatResponse(
                message = ChatMessage.Assistant(
                    content = accumulatedText.toString(),
                    toolCalls = toolCalls,
                ),
                usage = usage,
                finishReason = finishReason!!,
            )
        }
    }
}
