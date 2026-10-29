package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.log.log
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.memory.RepairedMemory
import io.github.yeyi.agent.memory.RoundsBoundedMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.memory.addToMemory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.tool.AsyncTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
) : Agent, Agent.Streamable, Agent.Steerable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)
    private val steerInbox = SteerInbox()

    override fun run(query: AgentQuery): Flow<AgentEvent> {
        acquireSteerInbox()
        return flow {
            loop(query, { req -> llmProvider.chat(req) }, { emit(it) })
        }
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> {
        acquireSteerInbox()
        return flow {
            val runner = StreamingRunner(llmProvider)
            loop(
                query = query,
                llmCall = { req -> runner.run(req) { emit(AgentEvent.TextDelta(it)) } },
                emit = { emit(it) }
            )
        }
    }

    /**
     * [SteerInbox.lock] 序列化 [SteerInbox.add] 与终局 isEmpty 裁决+终态发射+关门，堵死
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
    override fun steer(query: AgentQuery): Boolean = steerInbox.add(query)

    private suspend fun loop(
        query: AgentQuery,
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
                loopOnce(++iterations, toolCalls, llmCall, emit)?.let { return }
            }

            throw AgentException.MaxIterations(maxIterations)
        } catch (cause: Throwable) {
            if (cause is kotlinx.coroutines.CancellationException) {
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
        } finally {
            // 兜底销毁（幂等，正常 Final 路径已在检查点②销毁过）。
            steerInbox.destroy()
        }
    }

    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        if (!steerInbox.isEmpty()) consumeSteering(steerInbox)

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
            return finalize(response, iterations, toolCalls, context, emit)
        }

        val allToolAsync = response.message.toolCalls
            .all { call -> toolRegistry.getOrNull(call.name) is AsyncTool }

        callTools(response, toolCalls, allToolAsync, context, emit)

        if (allToolAsync) {
            val emptyResponse = ChatResponse(ChatMessage.Assistant(), null, FinishReason.Stop)
            emptyResponse.message.addToMemory(memory)
            return finalize(emptyResponse, iterations, toolCalls, context, emit)
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
        context: AgentContext,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult? {
        // 终局：Final 抢占 + 终局。与 steer() 闸门下互斥。
        steerInbox.gate.acquireUninterruptibly()
        if (!steerInbox.isEmpty()) {
            steerInbox.gate.release()
            // 指令已注入 memory，抢占 Final，强制再跑一轮让 LLM 带着新指令重新作答
            consumeSteering(steerInbox)
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
            // 终局：终态发射 → 关门，全程持闸。
            // 闸门释放之后 steer() 拿到的 false 才意味着"可安全开新 run"——
            // 若把 emit(Final) 放闸外，缝隙里 steer()==false 触发的 fallback run
            // 会先发 Initial，旧 run 的 Final 后到，混流。
            // 先发射后关门：emit 抛异常时此处 destroy 不执行，由 loop 的 finally
            // 兜底清理——保证 false ⟹ Final 已发射的严格性（发射失败即失败路径，
            // 退化为 best-effort）。
            steerInbox.destroy()
        } finally {
            // 闸门用 Semaphore: release 可由任意线程执行 —— emit 是挂起点,
            // 协程恢复后可能换线程, 线程亲和锁在此 unlock 会抛 IllegalMonitorStateException。
            steerInbox.gate.release()
        }
        return result
    }

    /**
     * eager 占据活跃 run 槽位：调用即建信箱，杜绝 "run 返回但未收集" 间隙里
     * steer() 误判空闲导致并发双 run。已有活跃 run 时抛并发异常。
     */
    private fun acquireSteerInbox() {
        if (!steerInbox.create()) error("Concurrent run not supported: another run is active")
    }

    private suspend fun consumeSteering(steerInbox: SteerInbox) {
        steerInbox.drain().forEach { query ->
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
        val messages = modalityAdapter.resolve(memory.history().map { labelPrefixed(it) })
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(messages)
            },
            tools = toolRegistry.all().map(Tool::toDefinition)
        )
    }

    /** 带 label 的上行消息（User / System）拼 `"label: content"` 前缀给模型感知来源，
     *  持久化 content 保持原样。下行消息不会带 label，原样返回。 */
    private fun labelPrefixed(entry: MemoryEntry): ChatMessage {
        val label = entry.label ?: return entry.message
        return when (val message = entry.message) {
            // User 是 parts 列表，只给首个 Text 块拼，媒体块不被污染
            is ChatMessage.User -> message.copy(parts = message.parts.mapIndexed { i, part ->
                if (i == 0 && part is ContentPart.Text) ContentPart.Text("$label: ${part.text}") else part
            })

            is ChatMessage.System -> message.copy(content = "$label: ${message.content}")
            else -> message
        }
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

    private class SteerInbox {
        private val channelRef = AtomicReference<Channel<AgentQuery>?>(null)

        /**
         * 终局闸门 —— 互斥"抢占 Final"与"投递 steering"两个动作.
         *
         * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口会跨越挂起点 ([emit] 是 suspend),
         * 协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
         * IllegalMonitorStateException —— 表现为 agent 在已发出 Final 之后又报 Failed。
         * Semaphore 的 permit 与线程无关, release 可由任意线程执行。
         *
         * 代价: 不可重入 —— collector / AgentHook 回调内同步调用 steer() 会死锁
         * (本就是硬约束, 见 [steer] 的 KDoc)。
         */
        val gate = Semaphore(1)

        fun create(): Boolean = channelRef.compareAndSet(null, Channel(Channel.UNLIMITED))

        fun destroy() = channelRef.getAndSet(null)?.close()

        fun add(query: AgentQuery): Boolean {
            gate.acquireUninterruptibly()
            try {
                return channelRef.get()?.trySend(query)?.isSuccess ?: false
            } finally {
                gate.release()
            }
        }

        fun drain(): List<AgentQuery> {
            gate.acquireUninterruptibly()
            try {
                return buildList {
                    while (true) {
                        val query = channelRef.get()?.tryReceive()?.getOrNull() ?: return@buildList
                        add(query)
                    }
                }
            } finally {
                gate.release()
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun isEmpty(): Boolean = channelRef.get()?.isEmpty ?: true
    }
}
