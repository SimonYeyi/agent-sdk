package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentResultExtensionsTest {

    @Test
    fun `awaitResult returns AgentResult from Final event`() = runTest {
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hi"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5))
        val result = agent.run("hello", InMemoryMemory()).awaitResult()
        assertEquals("hi", result.message.content)
        assertEquals(1, result.iterations)
        assertEquals(emptyList(), result.toolCalls)
    }

    @Test
    fun `awaitResult filters non-Final events and finds Final`() = runTest {
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
    fun `awaitResult throws when no Final event present`() = runTest {
        val flow = flowOf<AgentEvent>(AgentEvent.Failed(RuntimeException("boom")))
        assertFailsWith<NoSuchElementException> {
            flow.awaitResult()
        }
    }
}
