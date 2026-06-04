package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.agent.fakes.EchoTool
import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.Role
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.memory.InMemoryMemory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun `tool call cycle invokes tool and continues until final answer`() = runTest {
        val echo = EchoTool()
        val toolCall = ToolCall(
            id = "c1", name = "echo",
            arguments = JsonObject(mapOf("text" to JsonPrimitive("hello")))
        )
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = null, toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "done: hello"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5))
        val mem = InMemoryMemory()
        val result = agent.run("hi", mem)
        assertEquals("done: hello", result.finalMessage.content)
        assertEquals(2, result.iterations)
        assertEquals(1, result.toolCalls.size)
        assertEquals("echo", result.toolCalls[0].toolName)
        assertEquals(1, echo.invocations.size)
        val h = mem.history()
        assertEquals(4, h.size)
        assertEquals(Role.Tool, h[2].role)
        assertEquals("hello", (h[2] as ChatMessage.ToolResult).content)
    }

    @Test
    fun `multiple tool calls in single response are all invoked`() = runTest {
        val echo = EchoTool()
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = null, toolCalls = listOf(
                        ToolCall("c1", "echo", JsonObject(mapOf("text" to JsonPrimitive("a")))),
                        ToolCall("c2", "echo", JsonObject(mapOf("text" to JsonPrimitive("b"))))
                    )),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5))
        agent.run("hi", InMemoryMemory())
        assertEquals(2, echo.invocations.size)
    }
}
