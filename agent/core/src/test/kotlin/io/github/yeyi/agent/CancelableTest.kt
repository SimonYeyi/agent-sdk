package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 回合中断（[Agent.Cancelable]）的行为约定：
 * - 打断只终止 agent 自身的执行，收集方协程不受影响；
 * - 被打断的回合以 [AgentEvent.Canceled] 终局事件收尾 —— 不发 [AgentEvent.Failed]；
 * - 取消订阅只掐观察，回合照常执行（孤儿回合）；
 * - 打断不消耗 agent 的可用性——后续回合照常执行。
 */
class CancelableTest {

    /** 首次调用挂起在 gate 上，用于构造"回合进行中"的时序。 */
    private class GatedLlm : LlmProvider {
        override val name: String = "gated"
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()
        var callCount = 0
            private set

        fun release() = gate.complete(Unit)

        override suspend fun chat(request: ChatRequest): ChatResponse {
            callCount++
            if (callCount == 1) {
                started.complete(Unit)
                gate.await()
            }
            return ChatResponse(
                message = ChatMessage.Assistant(content = "done"),
                finishReason = FinishReason.Stop
            )
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            callCount++
            if (callCount == 1) {
                started.complete(Unit)
                gate.await()
            }
            emit(ChatResponseEvent.ContentDelta("done"))
            emit(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    /** 被取消后"收尾很慢" —— 用于观察打断与槽位释放的时序。 */
    private class SlowUnwindLlm : LlmProvider {
        override val name: String = "slow-unwind"
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            started.complete(Unit)
            try {
                gate.await()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { delay(300) } // 模拟收尾耗时
                throw e
            }
            return ChatResponse(
                message = ChatMessage.Assistant(content = "done"),
                finishReason = FinishReason.Stop
            )
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            emit(ChatResponseEvent.ContentDelta("done"))
            emit(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    private fun TestScope.agentOf(provider: LlmProvider) = ReActAgent(
        id = "test",
        persona = AgentPersona(""),
        llmProvider = provider,
        toolRegistry = registryOf(),
        memory = InMemoryMemory(),
        modalityAdapter = DefaultModalityAdapter(null),
        maxRounds = 20,
        maxIterations = 5,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** 一条良构的非流式脚本响应。 */
    private fun okResponse() =
        ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)

    @Test
    fun `cancel interrupts in-flight run and the collector sees the cancellation`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            agent.run(AgentQuery.text("hi")).collect { events += it }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        // 终局是 Canceled 事件（不是取消异常，也不是静默零事件）。
        assertTrue(events.last() is AgentEvent.Canceled)
        // 打断的回合没有其他终态事件。
        assertTrue(events.none { it is AgentEvent.Final })
        assertTrue(events.none { it is AgentEvent.Failed })
        // 收集方协程本身未被取消。
        assertFalse(job.isCancelled)
    }

    @Test
    fun `cancel reason surfaces to the collector of an in-flight run`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            agent.run(AgentQuery.text("hi")).collect { events += it }
        }
        llm.started.await()
        agent.cancel("shut down by user")
        job.join()

        // 打断原因随 Canceled 事件送达收集方（诊断用途，非消费契约）。
        val canceled = events.last() as AgentEvent.Canceled
        assertTrue("shut down by user" in (canceled.cause.message ?: ""), "实际消息: ${canceled.cause.message}")
        // 与普通 cancel 一致：收集方协程本身未被取消。
        assertFalse(job.isCancelled)
    }

    @Test
    fun `cancel interrupts in-flight streaming run`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()

        val job = launch {
            agent.runStream(AgentQuery.text("hi")).collect { events += it }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        assertTrue(events.last() is AgentEvent.Canceled)
    }

    @Test
    fun `cancel without an active round does not affect the next round`() = runTest {
        val agent = agentOf(FakeLlmProvider(nonStreamResponses = listOf(okResponse())))

        agent.cancel()

        val events = agent.run(AgentQuery.text("hi")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    /** 取消订阅只终止该订阅；回合在 agent scope 中继续执行，槽位由执行体照常归还。 */
    @Test
    fun `cancelling the collector only cancels the subscription`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                agent.run(AgentQuery.text("hi")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        llm.started.await()
        job.cancel() // 外部取消收集方协程
        job.join()

        // 收集方自身的 collect 以取消异常结束（标准协程语义，不属于终局事件）。
        assertTrue(caught.await() is CancellationException)

        // 回合不受影响：仍在 LLM 上挂着 —— 槽位未还，此刻开新轮撞并发拒绝。
        val ex = assertFailsWith<IllegalStateException> {
            agent.run(AgentQuery.text("again")).toList()
        }
        assertTrue(ex.message!!.contains("Concurrent run not supported"))

        // 释放 gate 后回合自然终结，槽位由执行体 finally 归还 → 新轮照常执行。
        llm.release()
        advanceUntilIdle()
        val events = agent.run(AgentQuery.text("again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    @Test
    fun `agent remains usable after a cancelled round`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)

        val job = launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    /**
     * 回归：收尾期间的第二次 cancel() 不得释放槽位。
     *
     * 打断只投递取消，执行体还可能跑着一段收尾 —— 此刻放掉槽位，新一轮就能与
     * 正在死去的旧轮并发。
     */
    @Test
    fun `second cancel while unwinding does not free the slot early`() = runTest {
        val llm = SlowUnwindLlm()
        val agent = agentOf(llm)

        val job = launch {
            agent.run(AgentQuery.text("first")).collect { }
        }
        llm.started.await()

        agent.cancel() // 打断；轮次进入慢收尾
        agent.cancel() // 幂等的第二次 —— 不应放掉槽位

        // 旧轮仍在收尾 → 槽位仍被占用 → 此刻开新轮应报并发异常
        val ex = assertFailsWith<IllegalStateException> {
            agent.run(AgentQuery.text("second")).toList()
        }
        assertTrue(ex.message!!.contains("Concurrent run not supported"))
        job.join()
    }

    /** 收集方自己的异常只终止该订阅；回合照常跑完、槽位照常归还。 */
    @Test
    fun `an exception thrown by the collector must not skip the round's release`() = runTest {
        val provider = FakeLlmProvider(nonStreamResponses = listOf(okResponse(), okResponse()))
        val agent = agentOf(provider)

        // 收集方自己的代码抛异常（消费端 bug）—— 订阅被终止，但回合并不知道。
        runCatching {
            agent.run(AgentQuery.text("hi")).collect { throw IllegalStateException("consumer bug") }
        }
        advanceUntilIdle()

        // 槽位已归还 → 下一轮照常执行。
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    /**
     * 打断后重开：等收尾句柄 → 旧轮彻底退场 → 新轮必定能起来。
     *
     * 句柄在「还槽 + 关信箱」之后完成 —— join 之后新轮稳起。
     */
    @Test
    fun `joining the cancel handle makes the next round startable`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)

        val job = launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }
        llm.started.await()

        val handle = assertNotNull(agent.cancel())
        handle.join()

        // 旧轮已退场（槽位已还、信箱已关）→ 新轮必定能起来
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
        // 信箱已关 → steer 无处可投
        assertFalse(agent.steer(AgentQuery.text("later")))
        job.join()
    }

    /** 无活跃轮次时打断是幂等 no-op —— 没有句柄可给。 */
    @Test
    fun `cancel without an active round returns no handle`() = runTest {
        val agent = agentOf(FakeLlmProvider(nonStreamResponses = listOf(okResponse())))

        assertNull(agent.cancel())
    }

    // ===== 架构 2 新语义 =====

    /** run 后不 collect，回合照常执行、槽位照常归还。 */
    @Test
    fun `run without collection still executes and retires the slot`() = runTest {
        val provider = FakeLlmProvider(nonStreamResponses = listOf(okResponse(), okResponse()))
        val agent = agentOf(provider)

        agent.run(AgentQuery.text("hi")) // 不 collect
        advanceUntilIdle()

        assertEquals(1, provider.recordedRequests.size, "run 即执行，无需订阅")
        // 新一轮照常执行，收集拿到 Initial → Final 完整序列。
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
        assertEquals(2, provider.recordedRequests.size, "第二轮照常执行（槽位已还）")
    }

    /** 取消订阅不终止回合（孤儿回合）——回合仍完成，重新收集可取到完整历史与终局。 */
    @Test
    fun `unsubscribing does not terminate the orphan round`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val job = launch { runCatching { flow.collect { } } }
        llm.started.await()
        job.cancel() // 只取消订阅
        llm.release() // 释放 gate，回合全速跑完

        val events = mutableListOf<AgentEvent>()
        flow.collect { events += it } // 重新订阅：回放全历史 + 终局
        assertTrue(events.first() is AgentEvent.Initial)
        assertTrue(events.last() is AgentEvent.Final)
        job.join()
    }

    /** 同一条公共流第二次 collect：完整历史 + 终局，不重复执行。 */
    @Test
    fun `collecting the same flow twice replays history without re-execution`() = runTest {
        val provider = FakeLlmProvider(nonStreamResponses = listOf(okResponse()))
        val agent = agentOf(provider)
        val flow = agent.run(AgentQuery.text("hi"))

        val first = flow.toList()
        advanceUntilIdle()
        val second = flow.toList()

        assertEquals(first, second)
        assertEquals(1, provider.recordedRequests.size, "不得重复执行")
    }

    /** 两个订阅者各自收到完整序列 —— 广播不分流。 */
    @Test
    fun `two subscribers each receive the complete sequence`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val a = mutableListOf<AgentEvent>()
        val b = mutableListOf<AgentEvent>()
        val subA = launch { flow.collect { a += it } }
        llm.started.await()
        val subB = launch { flow.collect { b += it } } // 中途订阅
        llm.release()

        subA.join(); subB.join()
        assertEquals(a, b, "两个订阅者序列同构（b 为回放段 + live 段）")
        assertTrue(a.first() is AgentEvent.Initial)
        assertTrue(a.last() is AgentEvent.Final)
    }

    /** 迟到订阅必见 Initial —— 回放从回合第一个事件开始。 */
    @Test
    fun `late subscriber sees Initial first`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val early = launch { flow.collect { } }
        llm.started.await()
        llm.release()
        early.join() // 回合已终局

        val events = mutableListOf<AgentEvent>()
        flow.collect { events += it }
        assertTrue(events.first() is AgentEvent.Initial, "终局后订阅仍从 Initial 开始")
        assertTrue(events.last() is AgentEvent.Final)
    }

    /** steer()==false 后立刻 run 必定成功（顺序收集零交错）。 */
    @Test
    fun `run immediately after steer returns false always starts`() = runTest {
        val provider = FakeLlmProvider(nonStreamResponses = listOf(okResponse(), okResponse()))
        val agent = agentOf(provider)

        agent.run(AgentQuery.text("one")).toList() // 终局后 steer 必 false
        assertFalse(agent.steer(AgentQuery.text("late")))
        assertEquals(1, agent.run(AgentQuery.text("two")).toList().filterIsInstance<AgentEvent.Final>().size)
    }

    /** 终局唯一：取消与 Final 竞态下每个订阅者恰好看到一个终局事件。 */
    @Test
    fun `each subscriber sees exactly one terminal event`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val a = mutableListOf<AgentEvent>()
        val b = mutableListOf<AgentEvent>()
        val subA = launch { flow.collect { a += it } }
        val subB = launch { flow.collect { b += it } }
        llm.started.await()
        agent.cancel()
        subA.join(); subB.join()

        assertEquals(1, a.count { it.isTerminal })
        assertEquals(1, b.count { it.isTerminal })
    }

    /** shutdown 后：活跃回合收 Canceled("Agent is shut down")，run 抛 ISE，幂等。 */
    @Test
    fun `shutdown cancels the in-flight round with a shutdown-cause event and rejects further runs`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.run(AgentQuery.text("hi")).collect { events += it } }
        llm.started.await()

        agent.shutdown()
        agent.shutdown() // 幂等
        job.join()

        val last = events.last()
        assertTrue(last is AgentEvent.Canceled, "实际终局: ${events.map { it::class.simpleName }}")
        // shutdown 诊断沿 cause.cause 链传递：ReActAgent.shutdown() 构造 CancellationException(null, ShutdownError)，
        // 直接读 cause.message 会拿到 null —— 必须沿 cause 链反查 ShutdownError 确认关闭语义。
        assertTrue(
            generateSequence<Throwable>((last as AgentEvent.Canceled).cause) { it.cause }
                .any { it is Agent.Shutdownable.ShutdownError },
            "实际 cause 链: ${(last as AgentEvent.Canceled).cause}"
        )
        val ex = assertFailsWith<IllegalStateException> { agent.run(AgentQuery.text("again")) }
        assertTrue(ex.message!!.contains("Agent is shut down"))
    }

    /**
     * 回归：run 与 shutdown 的窄窗口不得产生「无终局」流。
     *
     * runTurn 的 `check(scope.isActive)` 通过之后、执行体 job 被调度之前，
     * 若 shutdown 的 scope.cancel() 抢先执行，LAZY job 变成 born-cancelled：执行体不跑、
     * 终局不发 → 收集方永久挂起（静默失败，架构 2 明确要排除的模式）。
     *
     * 该窗口无法在单线程测试调度器上确定性钉住（run 内建 job 即入队、start 同步生效），
     * 故用多线程 runBlocking + 真实竞态：另一线程插入 shutdown，断言任何一次 run
     * 返回的流都在有限时间内以唯一终局收尾。未修复时，shutdown 落进窗口的轮次
     * 会以 withTimeout 超时暴露（实测未修复代码上稳定失败）。
     */
    @Test
    fun `run racing with shutdown always terminates the returned flow`() = runBlocking(Dispatchers.Default) {
        val iterations = 500
        for (i in 0 until iterations) {
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = FakeLlmProvider(nonStreamResponses = List(iterations) { okResponse() }),
                toolRegistry = registryOf(),
                memory = InMemoryMemory(),
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = Dispatchers.Default,
            )
            // 另起一线程 shutdown，与主线程的 run 抢「check 之后、执行体生效之前」的窗口。
            val killer = launch {
                repeat(i % 4) { yield() } // 交替偏移，制造不同交错点
                agent.shutdown()
            }

            // killer 若先到，run 同步抛 ISE 是契约行为（该轮从未开始，无流可收）。
            val flow = runCatching { agent.run(AgentQuery.text("hi")) }.getOrElse {
                assertTrue(it is IllegalStateException && "shut down" in it.message.orEmpty(), "非预期异常: $it")
                killer.join()
                continue
            }

            val events = mutableListOf<AgentEvent>()
            withTimeout(5_000) { flow.collect { events += it } }
            killer.join()

            assertTrue(
                events.lastOrNull()?.isTerminal == true,
                "第 $i 次：shutdown 抢在启动窗口时流必须仍以终局事件收尾，实际: ${events.map { e -> e::class.simpleName }}"
            )
            assertEquals(1, events.count { e -> e.isTerminal }, "第 $i 次：终局唯一")
        }
    }
}
