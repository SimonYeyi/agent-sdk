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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回合中断（[Agent.Cancelable]）的行为约定：
 * - 打断只终止 agent 自身的执行，收集方协程不受影响；
 * - 被打断的回合以 [kotlinx.coroutines.CancellationException] 结束收集 ——
 *   不发终态事件，也不以"零事件 + 正常结束"收尾（终局三选一：Final / Failed / 取消异常）；
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

    /** 每次 chat() 都挂起 —— 用于构造"运行中的轮次"，可反复打断。 */
    private class AlwaysGatedLlm : LlmProvider {
        override val name: String = "always-gated"
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            if (!started.isCompleted) started.complete(Unit)
            gate.await()
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

    private fun agentOf(provider: LlmProvider) = ReActAgent(
        persona = Persona(""),
        llmProvider = provider,
        toolRegistry = registryOf(),
        memory = InMemoryMemory(),
        modalityAdapter = DefaultModalityAdapter(null),
        maxRounds = 20,
        maxIterations = 5,
    )

    @Test
    fun `cancel interrupts in-flight run and the collector sees the cancellation`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                agent.run(AgentQuery.text("hi")).collect { events += it }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        // 取消异常原样上抛 —— 调用方沿用 collect 外围 try/catch 的既有用法。
        assertTrue(caught.await() is CancellationException)
        // 打断的回合没有终态事件。
        assertTrue(events.none { it is AgentEvent.Final })
        assertTrue(events.none { it is AgentEvent.Failed })
        // 收集方协程本身未被取消。
        assertFalse(job.isCancelled)
    }

    @Test
    fun `cancel interrupts in-flight streaming run`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                agent.runStream(AgentQuery.text("hi")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        assertTrue(caught.await() is CancellationException)
    }

    @Test
    fun `cancel before the round starts abandons the round`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        val flow = agent.run(AgentQuery.text("hi"))
        agent.cancel()

        // 回合从未被收集 → cancel 放弃该回合：收集时以取消异常收尾（与"运行中被打断"同型），
        // 绝不出现"零事件 + 正常结束"的静默出口，且不再调用 LLM。
        assertFailsWith<CancellationException> { flow.toList() }
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test
    fun `cancel releases the slot of a round that was never collected`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        // run() 即占槽，但流从未被收集 —— 没有 cancel 的话槽位永远不会被回收。
        agent.run(AgentQuery.text("hi"))
        agent.cancel()

        // 槽位已释放 → 下一轮不再撞上 "Concurrent run not supported"。
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    @Test
    fun `the abandoned round does not destroy the next round's mailbox`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        // 第一轮被放弃：信箱销毁、槽位还回。
        val abandoned = agent.run(AgentQuery.text("hi"))
        agent.cancel()
        // 第二轮占槽，信箱就位。
        agent.run(AgentQuery.text("hi again"))

        // 第一轮此时才被收集：以取消异常收尾，其兜底释放认人 —— 不得销毁第二轮的信箱。
        assertFailsWith<CancellationException> { abandoned.toList() }
        // 第二轮的信箱仍在 → steer 仍可注入。
        assertTrue(agent.steer(AgentQuery.text("later")))
    }

    @Test
    fun `cancel without an active round does not affect the next round`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        agent.cancel()

        val events = agent.run(AgentQuery.text("hi")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    @Test
    fun `cancelling the collector ends the round with a cancellation and releases the slot`() = runTest {
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

        // 收集方拿到取消异常（不是静默零事件），槽位由该轮流的 onCompletion 认人释放。
        assertTrue(caught.await() is CancellationException)
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    @Test
    fun `agent remains usable after a cancelled round`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)

        val job = launch {
            try {
                agent.run(AgentQuery.text("hi")).collect { }
            } catch (_: CancellationException) {
            }
        }
        llm.started.await()
        agent.cancel()
        job.join()

        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    /**
     * 回归：迟收集的废弃轮次不得夺走运行中轮次的执行体登记。
     *
     * 登记若挂在 agent 级全局单槽上，废弃轮次一被收集就会顶掉运行中轮的登记 ——
     * 此后运行中轮永远打断不掉，槽位还会被提前放掉。
     */
    @Test
    fun `late collection of an abandoned round must not steal the running round's cancel slot`() = runTest {
        val llm = AlwaysGatedLlm()
        val agent = agentOf(llm)

        // 第一轮：创建但不收集 → cancel 放弃 → 槽位归还
        val abandoned = agent.run(AgentQuery.text("first"))
        agent.cancel()

        // 第二轮：开跑并被收集（挂在 LLM 上）
        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                agent.run(AgentQuery.text("second")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        llm.started.await()

        // 关键：此时才收集已被放弃的第一轮
        assertFailsWith<CancellationException> { abandoned.toList() }

        // 期望：cancel() 打断的仍是正在运行的第二轮
        agent.cancel()
        job.join()

        assertTrue(
            caught.await() is CancellationException,
            "运行中的第二轮应被 cancel() 打断 —— 实际结果: ${caught.await()}"
        )
    }

    /**
     * 回归：收尾期间的第二次 cancel() 不得释放槽位。
     *
     * 打断只投递取消，执行体还可能跑着一段收尾 —— 此刻放掉槽位，新一轮就能与
     * 正在死去的旧轮并发。
     */
    @Test
    fun `second cancel must not free the slot while the cancelled round is still unwinding`() = runTest {
        val llm = SlowUnwindLlm()
        val agent = agentOf(llm)

        val job = launch {
            try {
                agent.run(AgentQuery.text("first")).collect { }
            } catch (_: CancellationException) {
            }
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
}
