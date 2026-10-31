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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.tool.AsyncTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

public class ReActAgent internal constructor(
    private val persona: Persona,
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
     * 轮次身份（[Turn]）是槽位的**内容**：槽位里有轮次，轮次不持有槽位。
     */
    private val activeTurn = AtomicReference<Turn?>(null)

    override fun run(query: AgentQuery): Flow<AgentEvent> = runTurn { turn, emit ->
        loop(query, turn, { req -> llmProvider.chat(req) }, emit)
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> = runTurn { turn, emit ->
        val streaming = StreamingRunner(llmProvider)
        loop(
            query = query,
            turn = turn,
            llmCall = { req -> streaming.run(req) { emit(AgentEvent.TextDelta(it)) } },
            emit = emit
        )
    }

    /**
     * 回合的公共骨架：占活跃槽位 → 交由可中断容器执行 → 无论何种终态都释放槽位。
     */
    private fun runTurn(body: suspend (turn: Turn, emit: suspend (AgentEvent) -> Unit) -> Unit): Flow<AgentEvent> {
        // eager 占据活跃 run 槽位：调用即建轮，杜绝 "run 返回但未收集" 间隙里
        // steer() 误判空闲导致并发双 run。已有活跃 run 时抛并发异常。
        val turn = Turn()
        if (!activeTurn.compareAndSet(null, turn))
            error("Concurrent run not supported: another run is active")
        return turn.flow.with { emit -> body(turn, emit) }.onCompletion { cause ->
            // 非拥有者的收集（重复收集）不得收尾：认人 CAS 会命中仍在运行的轮次，
            // 把它的槽位提前释放（可开出并发双跑），并关掉它的信箱（steer() 对活着的轮返回 false）。
            if (cause is RepeatedCollectionException) return@onCompletion
            // 还槽（认人）：槽位若已是新一轮则空操作 —— 不得误杀下一轮。
            activeTurn.compareAndSet(turn, null)
            // 轮次收尾：关信箱
            turn.steering.close()
        }
    }

    /**
     * 打断当前回合 —— 只终止 agent 自身的执行，收集方协程不受影响。
     *
     * 回合从未被收集时（run() 已返回、流被放弃）：没有执行体可打断，而槽位也无人回收
     * —— agent 会被永久锁死，之后每次 run() 都抛 "Concurrent run not supported"。
     * 此时 cancel() 作废该轮（置已取消标志 + 关信箱）、把槽位还回去；该轮之后若被收集，
     * 以取消异常收尾（与运行中被打断同型）。
     */
    override fun cancel() {
        val turn = activeTurn.get() ?: return // 无活跃轮次 → no-op（幂等）
        // true  ⟺ 该轮已被收集 ⟹ 打断已投递，槽位由该轮流的 onCompletion 认人归还。
        // false ⟺ 该轮从未被收集 ⟹ 没有 onCompletion 会来，槽位必须在此归还。
        if (turn.flow.cancel()) return
        // 作废与还槽一起入闸：闸把「关信箱 + 还槽」对 steer() 变成原子的一步 ——
        // 后来的 steer() 拿到闸时信箱已关、槽位已空 → 投递失败 → 重读槽位 → 空 → 返回 false，
        // 既不死循环，也满足「false ⟹ 可开新轮」。
        // 该轮从未被收集 ⇒ 没有执行体 ⇒ 闸必空闲。
        turn.lock()
        try {
            turn.steering.close()
            activeTurn.compareAndSet(turn, null) // 还槽（认人）
        } finally {
            turn.unlock()
        }
    }

    /**
     * [Turn.lock] 序列化 [SteerInbox.add] 与终局 isEmpty 裁决+终态发射+关门，堵死
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
            if (turn.steering.add(query)) return true
        }
    }

    private suspend fun loop(
        query: AgentQuery,
        turn: Turn,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(query))

            memory.attachHook(MemoryCompressProxyHook(hook, emit), buildContext(0))
            modalityAdapter.archive(ChatMessage.User(query.parts)).addToMemory(memory, query.label)

            while (iterations < maxIterations) {
                loopOnce(++iterations, toolCalls, turn, llmCall, emit)?.let { return }
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
            hook.safeInvoke { onRunFailed(buildContext(iterations), cause) }
            emit(AgentEvent.Failed(cause))
        }
    }

    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        turn: Turn,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        if (!turn.steering.isEmpty()) consumeSteering(turn)

        val context = buildContext(iterations)
        val request = buildRequest()

        val finalRequest = hook.safeInvoke { beforeLlmCall(context, request) } ?: request
        val response = llmCallWithContextOverflowHandle(finalRequest, llmCall)
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
            return finalize(response, iterations, toolCalls, turn, context, emit)
        }

        val allToolAsync = response.message.toolCalls
            .all { call -> toolRegistry.getOrNull(call.name) is AsyncTool }

        callTools(response, toolCalls, allToolAsync, context, emit)

        if (allToolAsync) {
            val emptyResponse = ChatResponse(ChatMessage.Assistant(), null, FinishReason.Stop)
            emptyResponse.message.addToMemory(memory)
            return finalize(emptyResponse, iterations, toolCalls, turn, context, emit)
        }

        return null
    }

    private suspend fun callTools(
        response: ChatResponse,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        allAsync: Boolean,
        context: AgentContext,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        emit(
            AgentEvent.ToolCallExplanation(
                text = response.message.content?.takeIf { it != "" },
                toolCalls = response.message.toolCalls,
                allAsync = allAsync,
            )
        )

        for (call in response.message.toolCalls) {
            emit(AgentEvent.ToolCallStart(call))
            val synthetic = hook.safeInvoke { beforeToolCall(context, call) }
            val startMs = System.currentTimeMillis()
            val raw = synthetic?.copy(isError = true) ?: toolRegistry.dispatch(
                call.name,
                call.arguments,
                ToolExecutionContext(call.id, context)
            )
            val durMs = System.currentTimeMillis() - startMs
            val final = hook.safeInvoke {
                afterToolCall(context, call, raw, synthetic != null, durMs)
            } ?: raw

            toolCalls += AgentResult.ToolCallRecord(
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

            emit(AgentEvent.ToolCallEnd(call, final))
        }
    }

    /**
     * 终局协议：steer 检查点 → onRunCompleted → Final → destroy。
     * 正常收敛与 [AsyncTool] 短路共用，保证协议一致性。
     * 若在途指令 (steer) 非空，放弃终局返回 null，强制再跑一轮让 LLM 带着指令重新作答。
     */
    private suspend fun finalize(
        response: ChatResponse,
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        turn: Turn,
        context: AgentContext,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult? {
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
            iterations = iterations,
            toolCalls = toolCalls.toList(),
            usage = response.usage,
        )
        hook.safeInvoke { onRunCompleted(context, result) }
        try {
            emit(AgentEvent.Final(result))
            // 终局：终态发射 → 还槽 + 关门，全程持闸。
            // 闸门释放之后 steer() 拿到的 false 才意味着"可安全开新 run"——
            // 若把 emit(Final) 放闸外，缝隙里 steer()==false 触发的 fallback run
            // 会先发 Initial，旧 run 的 Final 后到，混流。
            // 先还槽、后关门：保证恒有「信箱已关 ⟹ 槽位已空」，steer() 的重试循环靠这条必然推进。
            // 关的是信箱而不是 turn.close()：此刻执行体正是在跑的那个自身，打断自己会
            // 把成功轮次变成取消轮次。
            activeTurn.compareAndSet(turn, null)   // 还槽（认人）
            turn.steering.close()                    // 关信箱（幂等）
        } finally {
            // 闸门用 Semaphore: release 可由任意线程执行 —— emit 是挂起点,
            // 协程恢复后可能换线程, 线程亲和锁在此 unlock 会抛 IllegalMonitorStateException。
            turn.unlock()
        }
        return result
    }

    private suspend fun consumeSteering(turn: Turn) {
        turn.steering.drain().forEach { query ->
            // steer 注入的指令默认标 "steering"；调用方显式带 label 时优先采用
            modalityAdapter.archive(ChatMessage.User(query.parts))
                .addToMemory(memory, label = query.label ?: "steering")
        }
    }

    private suspend fun llmCallWithContextOverflowHandle(
        request: ChatRequest,
        llmCall: suspend (ChatRequest) -> ChatResponse
    ): ChatResponse {
        return try {
            llmCall(request)
        } catch (e: Throwable) {
            e.takeIf { it.isContextOverflow() }
                ?.let { log.warn(it) }
                ?.let { memory.handleContextOverflow() }
                ?.let { llmCallWithContextOverflowHandle(buildRequest(), llmCall) }
                ?: throw e
        }
    }

    private suspend fun buildRequest(): ChatRequest {
        val messages = modalityAdapter.resolve(memory.history().map { it.toChatMessage() })
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

    /**
     * 一轮 run —— 轮次身份，也是本轮状态的唯一所有者。
     *
     * 持有终局闸：闸保护的是"本轮的终局裁决"（判空 → 发 Final → 关信箱 → 还槽），
     * 终局路径调的就是 `turn.lock()` —— 语义准确；信箱的投递 / 排空也要用它，
     * 于是信箱持有本轮的引用。
     *
     * 两个成员直接公开：调用方用它们的机械能力（投递 / 排空 / 判空 / 关闭、登记 / 打断），
     * 不在 Turn 上再铺一层转发方法 —— 转发只是把调用点搬家，没有多出语义。
     */
    private class Turn {
        /**
         * 终局闸门 —— 互斥"抢占 Final"与"投递 steering"两个动作.
         *
         * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口会跨越挂起点 ('emit' 是 suspend),
         * 协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
         * IllegalMonitorStateException —— 表现为 agent 在已发出 Final 之后又报 Failed。
         * Semaphore 的 permit 与线程无关, release 可由任意线程执行。
         *
         * 代价: 不可重入 —— collector / AgentHook 回调内同步调用 steer() 会死锁
         * (本就是硬约束, 见 [steer] 的 KDoc)。
         */
        private val gate = Semaphore(1)
        val flow = CancelableFlow()
        val steering = SteerInbox(this)   // 构造期传 this 是安全的：信箱只在调用期用它取闸

        fun lock() = gate.acquireUninterruptibly()
        fun unlock() = gate.release()
    }

    /**
     * 转向消息收纳 —— 本轮的信箱，只做机械操作。
     *
     * 锁不在这里，而在 [Turn]：闸保护的是"本轮的终局裁决"，终局路径调的是 `turn.lock()`。
     * 本类的投递 / 排空也要用它，故持有本轮引用 —— 双向依赖，语义闭合。
     */
    private class SteerInbox(private val turn: Turn) {
        private val inbox = Channel<AgentQuery>(Channel.UNLIMITED)

        /** 投递在途指令。闸内执行，与终局裁决互斥。 */
        fun add(query: AgentQuery): Boolean {
            turn.lock()
            try {
                return inbox.trySend(query).isSuccess
            } finally {
                turn.unlock()
            }
        }

        /** 排空在途指令。闸内执行。 */
        fun drain(): List<AgentQuery> {
            turn.lock()
            try {
                val queries = mutableListOf<AgentQuery>()
                while (true) {
                    val query = inbox.tryReceive().getOrNull() ?: return queries
                    queries.add(query)
                }
            } finally {
                turn.unlock()
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun isEmpty(): Boolean = inbox.isEmpty

        fun close() = inbox.close()
    }

    /**
     * 可中断的执行容器 —— 把"在子协程里跑一段产生事件的逻辑，并允许外部打断"这套语义收在一处。
     *
     * 每轮一份 —— 执行体登记下沉到轮次，跨轮不再有可被顶掉的全局单槽。
     *
     * 设计要点：
     * - 执行体跑在收集方的 scope 里（[channelFlow] 的子协程），因此收集方被取消时，
     *   结构化并发照旧向下传导，执行体一并停止；
     * - [cancel] 只取消这一个子协程 —— 协程取消向下传播、不向上，收集方协程本身不被取消，
     *   但收集会因 [CancellationException] 而结束，沿用"collect 外围 try/catch 取消异常"的既有用法；
     */
    private class CancelableFlow {
        private enum class Attach { Ok, Canceled, Repeated }

        private val lock = Any()
        private var job: Job? = null
        private var canceled = false

        /**
         * 构造本轮的可取消流。
         *
         * [Attach.Repeated] 抛出的 [RepeatedCollectionException] 是"这次收集不是拥有者"的
         * 内部信号 —— 轮次收尾据此跳过，避免重复收集提前释放仍在运行的轮次的槽位与信箱。
         */
        fun <T> with(body: suspend (emit: suspend (T) -> Unit) -> Unit): Flow<T> = channelFlow {
            // LAZY 启动：执行体在 await() 之前不运行 —— 一旦开始收集，执行体必已登记进本轮，
            // 此后的 cancel() 不会落空。
            val producer = async(start = CoroutineStart.LAZY) { body { send(it) } }
            when (attach(producer)) {
                Attach.Ok -> Unit
                // 本轮已被取消作废、且从未被收集 → 不再执行；以取消异常收尾，
                // 收集方不会拿到"零事件 + 正常结束"的静默出口。
                Attach.Canceled -> throw CancellationException("flow is canceled")
                // 同一轮被重复收集（含跑完后再收集）—— 跑不起来的调用错误，不是运行时错误。
                Attach.Repeated -> throw RepeatedCollectionException()
            }
            // 取消异常原样上抛：打断与"执行体自身被取消"对收集方是同一种信号，
            // 调用方沿用 collect 外围 try/catch(CancellationException) 即可。
            producer.await()
        }.buffer(Channel.RENDEZVOUS)

        /**
         * 登记执行体 —— 与打断同锁，二者必有其一先发生：
         * 登记先于打断 ⟹ 打断拿到执行体并取消它；打断先于登记 ⟹ 流已取消，登记被拒。
         */
        private fun attach(job: Job): Attach = synchronized(lock) {
            when {
                // 曾收集过 —— 含已跑完后再收集一次。
                this.job != null -> Attach.Repeated
                // 被 cancel 作废、从未收集 → 迟收集。
                canceled -> Attach.Canceled
                else -> {
                    this.job = job; Attach.Ok
                }
            }
        }

        /**
         * 打断本轮执行体（对已结束的 job 是无害的空操作）。
         *
         * @return true = 取消成功，打断执行体；false = 取消失败，无执行体可取消
         */
        fun cancel(): Boolean = synchronized(lock) {
            canceled = true
            return job?.cancel()?.let { true } ?: false
        }
    }

    /**
     * 同一轮次被重复收集 —— 调用方错误。
     *
     * **抛出时机**：不是 [Agent.run] / [Agent.Streamable.runStream] 调用时，而是**收集时** ——
     * 违规的那一次 collect 一进入流就收到本异常。重复收集只有到收集时才会被发现：
     * `run()` 只建轮、不碰执行体，调用它本身不抛任何异常。
     *
     * **不转 [AgentEvent.Failed]**：Failed 的唯一产地是轮次执行体内部的失败路径，
     * 而重复收集发生在执行体启动之前（执行体 LAZY，从未运行）—— 这一轮没有产出任何
     * 事件，也就没有失败可言。正在运行的那一轮的收集者同样收不到这条失败：
     * 失败事件发在这一轮共享的流上，若因第二次收集伪造一条，污染的正是真正的收集者。
     *
     * 继承 [IllegalStateException]：对外仍表现为"调用方错误"，但内部可以精确识别 ——
     * 轮次收尾据此判断"这次收集是不是拥有者"，非拥有者的收集不得触碰仍在运行的轮次
     * 的槽位与信箱；用专属类型而非笼统的 ISE，是为了不和收集方自己抛出的
     * [IllegalStateException] 混淆。
     */
    private class RepeatedCollectionException :
        IllegalStateException("a turn flow may only be collected once")
}
