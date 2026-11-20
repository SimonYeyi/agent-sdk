@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedableTest {

    private fun TestScope.agentOf(
        provider: LlmProvider,
        mem: InMemoryMemory,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = ReActAgent(
        id = "test",
        persona = AgentPersona(""),
        llmProvider = provider,
        toolRegistry = registryOf(),
        memory = mem,
        modalityAdapter = DefaultModalityAdapter(null),
        maxRounds = 20,
        maxIterations = 5,
        dispatcher = dispatcher,
    )

    /**
     * 让 chat 挂起在 [gate] 上：run 启动即占槽、执行体在 LLM 处挂起，制造"活跃期"，
     * 用于断言 feed 拒绝对活跃期注入。
     */
    private class SuspendingLlmProvider(
        private val gate: CompletableDeferred<ChatResponse>,
    ) : LlmProvider {
        override val name: String = "suspending"
        override suspend fun chat(request: ChatRequest): ChatResponse = gate.await()
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {}
    }

    @Test
    fun `feed returns a joinable job when idle and writes the message into memory`() = runTest {
        val mem = InMemoryMemory()
        val agent = agentOf(
            FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
                )
            ),
            mem,
        )

        val job = agent.feed(AgentQuery.text("idle event"))
        assertNotNull(job, "idle feed should return a write job")
        job.join() // join 即确保写入协程落笔

        val written = mem.history().any { entry ->
            val msg = entry.message
            msg is ChatMessage.User &&
                    msg.parts.any { it is ContentPart.Text && it.text.contains("idle event") }
        }
        assertTrue(written, "fed message should be written into memory after joining the job")
    }

    @Test
    fun `fed message is visible in the next run's request`() = runTest {
        val mem = InMemoryMemory()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider, mem)

        val fed = assertNotNull(agent.feed(AgentQuery.text("pre-fed context")))
        fed.join() // join 确保喂入消息已进 memory，再启动 run

        agent.run(AgentQuery.text("hi")).toList()

        val fedInRequest = provider.recordedRequests.first().messages
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .any { it.text.contains("pre-fed context") }
        assertTrue(fedInRequest, "fed message should be included in the next run's first request")
    }

    @Test
    fun `feed returns null after shutdown instead of throwing`() = runTest {
        val mem = InMemoryMemory()
        val agent = agentOf(
            FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
                )
            ),
            mem,
        )

        agent.shutdown()
        assertNull(agent.feed(AgentQuery.text("after shutdown")))
    }

    @Test
    fun `feed refuses injection during an active run`() = runTest {
        val mem = InMemoryMemory()
        val gate = CompletableDeferred<ChatResponse>()
        val agent = agentOf(SuspendingLlmProvider(gate), mem)

        // run 启动即占槽（activeTurn 非空），执行体在 LLM 处挂起，保持活跃期
        agent.run(AgentQuery.text("in-flight"))
        assertNull(agent.feed(AgentQuery.text("during-active")), "active run must refuse feed")

        // 释放挂起点，让回合正常收尾，避免测试结束仍有挂起协程残留
        gate.complete(ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop))
    }
}