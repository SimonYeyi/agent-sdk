@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedableTest {

    private fun TestScope.agentOf(
        provider: FakeLlmProvider,
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

    @Test
    fun `feed returns true when idle and writes the message into memory`() = runTest {
        val mem = InMemoryMemory()
        val agent = agentOf(
            FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
                )
            ),
            mem,
        )

        assertTrue(agent.feed(AgentQuery.text("idle event")))
        // 异步写入：推进调度让 scope.launch 的后台协程落笔
        advanceUntilIdle()

        val written = mem.history().any { entry ->
            val msg = entry.message
            msg is ChatMessage.User &&
                    msg.parts.any { it is ContentPart.Text && it.text.contains("idle event") }
        }
        assertTrue(written, "fed message should be written into memory after the coroutine runs")
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

        assertTrue(agent.feed(AgentQuery.text("pre-fed context")))
        advanceUntilIdle() // 确保喂入消息已进 memory，再启动 run

        agent.run(AgentQuery.text("hi")).toList()

        val fedInRequest = provider.recordedRequests.first().messages
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .any { it.text.contains("pre-fed context") }
        assertTrue(fedInRequest, "fed message should be included in the next run's first request")
    }

    @Test
    fun `feed returns false when a round is active`() = runTest(UnconfinedTestDispatcher()) {
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        backgroundScope.launch { agent.run(AgentQuery.text("hi")).collect { } }
        advanceUntilIdle() // run 挂在 chat() 的 gate 上，轮次活跃

        assertFalse(agent.feed(AgentQuery.text("rejected")))

        provider.completeNext() // 释放 gate，收掉 run
        advanceUntilIdle()
    }

    @Test
    fun `feed returns false after shutdown instead of throwing`() = runTest {
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
        assertFalse(agent.feed(AgentQuery.text("after shutdown")))
    }
}