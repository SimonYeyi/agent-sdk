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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.tool.AsyncTool
import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.atomic.AtomicReference

/**
 * ReAct 循环 agent —— 本实现同一时刻至多一轮在跑，并发 [run] 被拒
 * （[IllegalStateException]，"Concurrent run not supported"）。
 */
public class ReActAgent internal constructor(
    private val id: String,
    private val persona: AgentPersona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Agent, Agent.Streamable, Agent.Steerable, Agent.Cancelable, Agent.Shutdownable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)

    /**
     * agent 私有执行 scope —— 回合生命周期归 agent，订阅只是观察。
     *
     * SupervisorJob：单回合失败只杀本轮 job，不废 agent；同时兼任关闭信号 ——
     * isActive 即"未关闭"，cancel 原子幂等触发关闭，无需独立 shutdownSignal 字段。
     * dispatcher 是 internal 注入口，仅供同模块测试注入 StandardTestDispatcher 保
     * runTest 虚拟时间；生产恒 Dispatchers.Default。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * 当前活跃轮次 —— 槽位按定义属于持有它的 agent。
     *
     * 轮次身份（[AgentTurn]）是槽位的**内容**：槽位里有轮次，轮次不持有槽位。
     */
    private val activeTurn = AtomicReference<AgentTurn?>(null)

    /**
     * 批式执行本轮 —— 轮次语义见 [Agent.run]。
     *
     * 终局事件（[AgentEvent.Final] / [AgentEvent.Failed] / [AgentEvent.Canceled]）
     * 发出时本轮已退场（槽位已还），故收到终局事件后紧接着再调本方法必定起得来。
     */
    override fun run(query: AgentQuery): Flow<AgentEvent> =
        runTurn(AgentTurn(query) { req -> llmProvider.chat(req) })

    /** 流式执行本轮 —— 轮次语义与 [run] 一致。 */
    override fun runStream(query: AgentQuery): Flow<AgentEvent> {
        val streaming = StreamingRunner(llmProvider)
        lateinit var turn: AgentTurn
        turn = AgentTurn(query) { req ->
            streaming.run(req) { turn.events.emit(AgentEvent.TextDelta(it)) }
        }
        return runTurn(turn)
    }

    /**
     * 投递在途指令 —— 与终局裁决互斥：[AgentTurn.lock] 序列化"投递"与
     * "判空 → 还槽 → 发终局事件"，堵死"指令被接受却没被消费"的窗口
     * （投递若挤进终局窗口，指令会被 Final 带走）。
     *
     * 投递失败契约：返回 false ⟺ 无活跃 run ⟺ 调用方可安全开新轮
     * （[Agent.run] 必定起得来 —— 终局路径先还槽再发终局事件，槽位先于 false 空出）。
     *
     * 纪律：闸内允许且仅允许 onRunCompleted 与 emit(Final) 两个已知挂起点，
     * 严禁其他 suspend——持闸挂起会阻塞其他线程的投递，编译器不拦，只能靠约定。
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
     * 打断当前回合 —— 只终止 agent 自身的执行，收集方协程不受影响
     * （回合以 [AgentEvent.Canceled] 终局事件收尾，收集方照常结束）。
     *
     * 启动即执行：cancel 恒打断在途执行体。回合若恰好已自然终局（job 已完成），
     * cancel 是无害空操作 —— 终局仍以 Final/Failed 呈现，不会伪造 Canceled。
     *
     * @param cause 打断原因（诊断用途）—— [AgentEvent.Canceled] 载荷携带该原因；
     *   为 null 时使用默认取消原因，见 [Agent.Cancelable.cancel]。
     *
     * @return 该轮执行体 job —— join 它即等旧轮退场（槽位已还、信箱已关），之后可安全地再次 run()；
     *   无活跃轮次时返回 null（幂等 no-op）。调用方可以 join 它等旧轮退场，
     *   也可以不等待 —— 不等待时收尾完成前再次 run() 仍可能撞上并发拒绝。
     */
    override fun cancel(cause: String?): Job? {
        val turn = activeTurn.get() ?: return null // 无活跃轮次 → no-op（幂等）
        // 收尾句柄就是执行体 job：活跃轮次的 job 恒非 null（先建 job 再 CAS 占槽），
        // join(job) 返回即执行体彻底结束 —— 槽位已还、信箱已关。
        return turn.job?.also { it.cancel(CancellationException(cause)) }
    }

    /**
     * 永久关闭 Agent —— 终态操作。取消 [scope]：活跃回合以 NonCancellable 发完
     * [AgentEvent.Canceled]（"Agent is shut down"）后退场，agent 私有执行域随之终止。
     * 幂等：重复调用无副作用。之后 [run]、[runStream] 抛 [ShutdownError]。
     */
    override fun shutdown() {
        scope.cancel(CancellationException(null, Agent.Shutdownable.ShutdownError())) // 幂等：重复调用无副作用
    }

    /**
     * 回合的公共骨架：占活跃槽位 → 在 agent scope 内启动执行体 → 返回广播流。
     * 启动即执行，收尾在执行体 —— 与订阅彻底解耦（孤儿回合语义）。
     *
     * job 必须先登记到 [AgentTurn.job]、再让轮次出现在 [activeTurn]：否则并发
     * [cancel] 读到活跃轮次却解引用到 null job，成了静默 no-op，该轮逃过取消。
     * 故用 [CoroutineStart.LAZY] 建 job（体不启动）→ 登记 job → CAS 占槽 → start()。
     * CAS 成功即建立 happens-before：写 job 先于写槽位（AtomicReference），
     * 经 [activeTurn] 观察到轮次的读者必能看到 job —— job 无需 @Volatile。
     * 顺序不可颠倒：CAS 之后才写 job，并发 [cancel] 会在窗口里读到 null job 静默落空。
     * CAS 失败路径无害：job 尚未 start，cancel 即丢弃。
     *
     * born-cancelled 兜底：窗口不止存在于 start 之前 —— start 同步返回后、执行体被
     * 调度执行之前，shutdown 仍可能抢先取消 job，见方法体。
     */
    private fun runTurn(turn: AgentTurn): Flow<AgentEvent> {
        if (!scope.isActive) throw Agent.Shutdownable.ShutdownError()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // 取消与 Final 不会双发：终局协议（还槽 → emit(Final)）到 job 结束之间
            // 没有挂起点（retireTurn 只是 CAS / close，emit 走无限缓冲不挂起），
            // job 不可能在此窗口被取消。
            // 回合级异常处理只此一层：loop 纯逻辑不自包 try-catch，
            // 取消/错误统一在此收口并事件化。
            try {
                loop(turn)
            } catch (e: CancellationException) {
                // 取消：修复孤儿记忆后以 Canceled 事件送达（NonCancellable 屏蔽取消态）。
                withContext(NonCancellable) {
                    // 修复失败只记日志：终局事件优先，不能因修复异常丢掉它。
                    runCatching { RepairedMemory.repairOrphans(memory, RepairedMemory.CANCELLED) }
                        .getOrElse { log.warn("repair orphans on cancel failed", it) }
                    // 先还槽、后发终局事件：终局事件发出即代表回合已结束、槽位已还，
                    // 收集方收到 Canceled 即可立即开下一轮。
                    retireTurn(turn)
                    turn.events.emit(AgentEvent.Canceled(e))
                }
            } catch (e: Throwable) {
                // 错误伪装成 Assistant 消息写入记忆：错误发生在助手侧执行链路，模型第二轮
                // 看到的是「我上一轮输出了错误」，可据此重试/澄清；UI 按 "error" label 渲染错误气泡。
                // 写入失败只记日志：终局事件优先，不能因写记忆失败丢掉它。
                try {
                    ChatMessage.Assistant(e.message ?: e.toString()).addToMemory(memory, "error")
                } catch (t: CancellationException) {
                    throw t // 取消优先于失败：取消态下写入失败仍须以 Canceled 收尾
                } catch (t: Throwable) {
                    log.warn("write error message to memory failed", t)
                }
                // 失败路径携带原始 Throwable,不再包成 AgentException —— 让上层自由判型。
                hook.safeInvoke { onRunFailed(turn.iterationContext, e) }
                retireTurn(turn)
                turn.events.emit(AgentEvent.Failed(e))
            }
        }
        // 先登记 job、再 CAS 占槽：写 job 先于写槽位，经 activeTurn 观察到轮次的读者
        // 必能看到 job（并发 cancel 不会解引用到 null）；CAS 失败即并发，job 尚未 start，cancel 即丢弃。
        turn.job = job
        if (!activeTurn.compareAndSet(null, turn)) {
            job.cancel()
            error("Concurrent run not supported: another run is active, rejected: ${turn.query}")
        }
        // 终局兜底：执行体从未跑起来（LAZY job 在派发前被取消）时，体内那三条
        // 终局路径都没执行过 —— 没有终局事件，收集方将永久挂起。
        // 槽位仍是本回合 ⟺ 还没人还过槽 ⟺ 终局事件尚未发出：补发终局 + 还槽；
        // 正常路径槽已还，判别名不成立即跳过，不会双发终局。
        // 走到这里的完成态必为取消（体从未跑 ⟺ 唯一完成方式是被取消），
        // 故 cause 必为取消异常 —— 用 as 而非 is 分支，不变量被破坏时立即 CCE fail-fast。
        job.invokeOnCompletion { cause ->
            if (activeTurn.get() !== turn) return@invokeOnCompletion
            // 先还槽、后发终局事件（与三条终局路径同一顺序）
            retireTurn(turn)
            turn.events.tryEmit(AgentEvent.Canceled(cause as CancellationException))
        }
        job.start()
        return turn.events.transformWhile { event -> emit(event); !event.isTerminal }
    }

    /**
     * 轮次退场 —— 还槽（认人）+ 关信箱。
     *
     * **幂等**，调用多处：
     * - [finalize] 在闸内调它（**发终局事件之前**）—— 投递被挡在闸外，
     *   故 `steer() == false` 蕴含"信箱已关、槽位已空"；
     * - 取消 / 失败路径在发终局事件之前调它；
     * - 执行体从未启动的兜底路径（见 [runTurn] 的终局兜底分支）调它。
     * 两件事各自幂等（认人 CAS、信箱 close），二次调用无害。
     *
     * **退场先于终局事件**：终局事件发出即代表回合已结束、槽位已还 ——
     * 收集方收到终局事件即可立即开下一轮（run() 必定起得来）。
     *
     * **不必在闸内**：闸护的是"终局裁决"与"投递"互斥，而退场时执行体已结束，
     * 唯一可能并发的只有投递 —— 两步之间被挤进一个投递，代价只是那轮 steer
     * 对已死的轮返回 true（与"cancel 之后 steer 仍可能返回 true"同一语义）。
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
    }

    private suspend fun loop(turn: AgentTurn) {
        // 本轮上下文：循环外的第 0 次 —— 第一次迭代之前就得有一个可读的。
        // 记忆压缩钩子挂在轮次开头，紧随其后的 query 归档写入就可能触发压缩
        // （老对话已超 maxRounds 时是常事），钩子要拿 context；
        // 失败路径同理 —— 迭代之前就失败时 onRunFailed 也要有。
        // 循环内每轮迭代由 loopOnce 刷新成当轮的上下文。
        turn.iterationContext = buildContext(turn.iterations)

        turn.events.emit(AgentEvent.Initial(turn.query))

        memory.attachHook(
            MemoryCompressProxyHook(hook) { turn.events.emit(it) },
            turn.iterationContext
        )
        modalityAdapter.archive(ChatMessage.User(turn.query.parts))
            .addToMemory(memory, turn.query.label)

        while (turn.iterations < maxIterations) {
            turn.iterations++
            loopOnce(turn)?.let { return }
        }

        throw AgentException.MaxIterations(maxIterations)
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
            // 下方可终结短路路径构造，外部消费者见到 null 即可断定来自可终结工具终结。
            // 这是内部不变式而非调用方参数问题，故用 check 抛 IllegalStateException。
            check(response.message.content.isNullOrBlank().not()) {
                "Final event must carry non-blank content: LLM returned a response with neither tool calls nor content."
            }
            return finalize(turn, response)
        }

        // allAsync 仅描述「本轮全是异步工具」（供消费方判断，不驱动短路）。
        val allToolAsync = response.message.toolCalls
            .all { call -> toolRegistry.getOrNull(call.name) is AsyncTool }

        callTools(turn, response, allToolAsync)

        // 可终结短路：裁决权完全在工具——本轮全部工具均为可终结工具，且各工具
        // finalizable(arguments, result) 基于自己本轮的执行结果与原始入参裁定可终结
        // （如失败拒绝终结、或按入参决定是否产卡），则直接进入终局协议，不再触发下一轮推理。
        // ReActAgent 不判断结果，只原样回传。
        // 各工具的执行结果反查自 turn.toolCalls 的 ToolCallRecord（callTools 已按序追加）；
        // 入参取自本轮 tool call，与执行时 dispatch 的入参一致。
        val allToolFinalizable = response.message.toolCalls.all { call ->
            val tool = toolRegistry.getOrNull(call.name)
            val result = turn.toolCalls.lastOrNull { it.callId == call.id }?.result
            tool is FinalizableTool && result != null && tool.finalizable(call.arguments, result)
        }
        if (allToolFinalizable) {
            val emptyResponse = ChatResponse(ChatMessage.Assistant(), null, FinishReason.Stop)
            emptyResponse.message.addToMemory(memory)
            return finalize(turn, emptyResponse)
        }

        return null
    }

    private suspend fun callTools(turn: AgentTurn, response: ChatResponse, allAsync: Boolean) {
        turn.events.emit(
            AgentEvent.ToolCallExplanation(
                text = response.message.content?.takeIf { it != "" },
                toolCalls = response.message.toolCalls,
                allAsync = allAsync,
            )
        )

        for (call in response.message.toolCalls) {
            turn.events.emit(AgentEvent.ToolCallStart(call))
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

            turn.events.emit(AgentEvent.ToolCallEnd(call, final))
        }
    }

    /**
     * 终局协议：steer 检查点 → onRunCompleted → 退场 → Final。
     * 正常收敛与可终结短路共用，保证协议一致性。
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
            // 终局：还槽 + 关门 → 终态发射，全程持闸。
            //
            // 顺序：**先还槽、后发终局事件** —— 终局事件一旦发出就代表回合已结束、
            // 槽位已还，收集方收到它即可立即开下一轮（run() 必定起得来）。
            // 闸护的仍是"投递"与"终局裁决"互斥：信箱在终局事件之前就关了，
            // 投递既进不了已死的轮，也不会出现"指令被接受却没被消费"。
            retireTurn(turn)   // 还槽 + 关信箱
            turn.events.emit(AgentEvent.Final(result))
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
        val messages = modalityAdapter.resolve(memory.history()).map { it.toChatMessage() }
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(messages)
            },
            tools = toolRegistry.all().map(Tool::toDefinition)
        )
    }

    private fun buildContext(currentIteration: Int) = AgentContext(
        agentId = id,
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
