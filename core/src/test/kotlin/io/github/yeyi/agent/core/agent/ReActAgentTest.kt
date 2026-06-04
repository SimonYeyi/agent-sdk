package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.Role
import io.github.yeyi.agent.core.memory.InMemoryMemory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReActAgentTest {
    @Test
    fun `single turn without tool call returns assistant message`() = runTest {
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hello"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            AgentConfig("you are helpful", client, emptyList(), { InMemoryMemory() }, 5)
        )
        val memory = InMemoryMemory()
        val result = agent.run("hi", memory)
        assertEquals("hello", result.finalMessage.content)
        assertEquals(1, result.iterations)
        assertEquals(0, result.toolCalls.size)
        val h = memory.history()
        assertEquals(2, h.size)
        assertEquals(Role.User, h[0].role)
        assertEquals(Role.Assistant, h[1].role)
    }

    @Test
    fun `request to LLM includes system prompt as first message`() = runTest {
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("ROLE", client, emptyList(), { InMemoryMemory() }, 5))
        agent.run("q", InMemoryMemory())
        val msgs = client.recordedRequests.single().messages
        assertEquals(Role.System, msgs[0].role)
        assertEquals("ROLE", (msgs[0] as ChatMessage.System).content)
    }
}
