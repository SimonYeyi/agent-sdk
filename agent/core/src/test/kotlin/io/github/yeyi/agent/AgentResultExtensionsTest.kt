package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AgentResultExtensionsTest {

    @Test
    fun `awaitResult returns AgentResult from Final event`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hi"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val result = agent.run(AgentQuery.text("hello")).awaitResult()
        assertEquals("hi", result.message.content)
        assertEquals(1, result.iterations)
        assertEquals(emptyList(), result.toolCalls)
    }

    @Test
    fun `awaitResult filters non-terminal events and finds Final`() = runTest {
        // 直接构造一个混有其他事件的 flow
        val result = AgentResult(
            message = ChatMessage.Assistant(content = "hello"),
            iterations = 1,
            toolCalls = emptyList(),
        )
        val flow = flowOf<AgentEvent>(
            AgentEvent.TextDelta("he"),
            AgentEvent.TextDelta("llo"),
            AgentEvent.Final(result),
        )
        assertEquals(result, flow.awaitResult())
    }

    @Test
    fun `awaitResult throws Failed_throwable when Failed event is the terminal event`() = runTest {
        // Failed 现在携带 Throwable 而非 AgentException;这里沿用 LlmError
        // (它仍是 Throwable),验证 throwable 原样传播,不被重新包装。
        val cause = AgentException.LlmError(RuntimeException("boom"))
        val flow = flowOf<AgentEvent>(
            AgentEvent.TextDelta("he"),
            AgentEvent.Failed(cause),
        )
        val thrown = assertFailsWith<AgentException> { flow.awaitResult() }
        assertSame(cause, thrown, "awaitResult must propagate the exact Failed.cause, not wrap it")
    }
}
