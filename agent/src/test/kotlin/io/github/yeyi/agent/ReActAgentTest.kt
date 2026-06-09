package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.error.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.Role
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReActAgentTest {
    @Test
    fun `single turn without tool call returns assistant message`() = runTest {
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hello"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            AgentConfig("you are helpful", client, emptyList(), { InMemoryMemory() }, 5, hooks = emptyList())
        )
        val memory = InMemoryMemory()
        val result = agent.run("hi", memory).awaitResult()
        assertEquals("hello", result.message.content)
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
        val agent = ReActAgent(AgentConfig("ROLE", client, emptyList(), { InMemoryMemory() }, 5, hooks = emptyList()))
        agent.run("q", InMemoryMemory()).awaitResult()
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
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5, hooks = emptyList()))
        val mem = InMemoryMemory()
        val result = agent.run("hi", mem).awaitResult()
        assertEquals("done: hello", result.message.content)
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
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5, hooks = emptyList()))
        agent.run("hi", InMemoryMemory()).awaitResult()
        assertEquals(2, echo.invocations.size)
    }

    @Test
    fun `tool throwing returns isError result without aborting agent`() = runTest {
        val failingTool = object : Tool {
            override val name = "boom"
            override val description = "always fails"
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(args: JsonElement, ctx: ToolContext): ToolExecutionResult {
                throw RuntimeException("kaboom")
            }
        }
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "boom", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "recovered"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(failingTool), { InMemoryMemory() }, 5, hooks = emptyList()))
        val mem = InMemoryMemory()
        val result = agent.run("hi", mem).awaitResult()
        assertEquals("recovered", result.message.content)
        val toolResult = mem.history().filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("kaboom"))
    }

    @Test
    fun `tool not found returns isError result`() = runTest {
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "missing", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 5, hooks = emptyList()))
        val mem = InMemoryMemory()
        agent.run("hi", mem).awaitResult()
        val toolResult = mem.history().filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("missing"))
        assertTrue(toolResult.content.contains("echo"))
    }

    @Test
    fun `exceeding max iterations throws MaxIterations`() = runTest {
        val toolResp = ChatResponse(
            ChatMessage.Assistant(toolCalls = listOf(
                ToolCall("c", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
            )),
            finishReason = FinishReason.ToolCalls
        )
        val client = FakeLlmClient(nonStreamResponses = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 2, hooks = emptyList()))
        val events = agent.run("hi", InMemoryMemory()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val ex = failed.cause as AgentException.MaxIterations
        assertEquals(2, ex.max)
    }

    @Test
    fun `cancellation inside tool propagates`() = runTest {
        val cancellingTool = object : Tool {
            override val name = "wait"
            override val description = ""
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(args: JsonElement, ctx: ToolContext): ToolExecutionResult {
                throw kotlinx.coroutines.CancellationException("cancelled inside tool")
            }
        }
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "wait", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(cancellingTool), { InMemoryMemory() }, 5, hooks = emptyList()))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            agent.run("hi", InMemoryMemory()).toList()
        }
    }

    @Test
    fun `runStream emits TextDelta and Final for plain answer`() = runTest {
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hel"),
                    StreamEvent.ContentDelta("lo"),
                    StreamEvent.Done(null)
                )
            )
        )
        val agent = ReActAgent(AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5, hooks = emptyList()))
        val events = agent.runStream("hi", InMemoryMemory()).toList()
        val texts = events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hel", "lo"), texts)
        val finals = events.filterIsInstance<AgentEvent.Final>()
        assertEquals(1, finals.size)
        assertEquals("hello", finals.single().result.message.content)
    }

    @Test
    fun `runStream handles tool call cycle`() = runTest {
        val echo = EchoTool()
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ToolCallStart(id = "c1", name = "echo"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "\"x\"}"),
                    StreamEvent.Done(null)
                ),
                listOf(
                    StreamEvent.ContentDelta("done"),
                    StreamEvent.Done(null)
                )
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5, hooks = emptyList()))
        val events = agent.runStream("hi", InMemoryMemory()).toList()
        assertTrue(events.any { it is AgentEvent.ToolCallStarted && it.toolName == "echo" })
        assertTrue(events.any { it is AgentEvent.ToolCallFinished })
        assertTrue(events.any { it is AgentEvent.Final })
        assertEquals(1, echo.invocations.size)
    }

    @Test
    fun `ToolCallStarted event is emitted before tool invocation`() = runTest {
        // 验证事件时序:Started 必须在 invokeTool 之前发出,Finished 之后
        val echo = EchoTool()
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ToolCallStart(id = "c1", name = "echo"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    StreamEvent.Done(null)
                ),
                listOf(
                    StreamEvent.ContentDelta("done"),
                    StreamEvent.Done(null)
                )
            )
        )
        val agent = ReActAgent(AgentConfig("", client, listOf(echo), { InMemoryMemory() }, 5, hooks = emptyList()))
        val events = agent.runStream("hi", InMemoryMemory()).toList()

        val startedIdx = events.indexOfFirst { it is AgentEvent.ToolCallStarted }
        val finishedIdx = events.indexOfFirst { it is AgentEvent.ToolCallFinished }
        assertTrue(startedIdx >= 0, "ToolCallStarted must be emitted")
        assertTrue(finishedIdx >= 0, "ToolCallFinished must be emitted")
        assertTrue(startedIdx < finishedIdx, "ToolCallStarted must precede ToolCallFinished")
    }

    @Test
    fun `runStream propagates Error event as thrown cause`() = runTest {
        val boom = RuntimeException("stream failed")
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hel"),
                    StreamEvent.Error(boom),
                    StreamEvent.Done(null)  // after Error: should not be reached
                )
            )
        )
        val agent = ReActAgent(AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5, hooks = emptyList()))
        val events = agent.runStream("hi", InMemoryMemory()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertSame(boom, failed.cause)
    }

    @Test
    fun `runStream exhausts max iterations`() = runTest {
        val toolResp = listOf(
            StreamEvent.ToolCallStart(id = "c", name = "echo"),
            StreamEvent.ToolCallDelta(id = "c", name = null, argumentsDelta = "{\"text\":\"x\"}"),
            StreamEvent.Done(null)
        )
        val client = FakeLlmClient(streamScripts = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 2, hooks = emptyList()))
        val events = agent.runStream("hi", InMemoryMemory()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val ex = failed.cause as AgentException.MaxIterations
        assertEquals(2, ex.max)
    }
}
