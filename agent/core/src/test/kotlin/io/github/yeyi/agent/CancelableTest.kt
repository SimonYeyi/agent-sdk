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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回合中断（[Agent.Cancelable]）的行为约定：
 * - 打断只终止 agent 自身的执行，收集方协程不受影响；
 * - 被打断的回合以零终态事件自然收尾，不向收集方抛异常；
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
    fun `cancel before the round starts is a no-op`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        val flow = agent.run(AgentQuery.text("hi"))
        agent.cancel()
        val events = flow.toList()

        // cancel 只打断"正在执行"的回合；尚未开始的回合收集时照常执行。
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
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
}
