package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.Role
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
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
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hello"), finishReason = FinishReason.Stop)
            )
        )
        val memory = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona("you are helpful"), llmProvider = provider, toolRegistry = registryOf(), memory = memory, maxRounds = 20, maxIterations = 5
        )
        val result = agent.run("hi").awaitResult()
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
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(persona = Persona("ROLE"), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        agent.run("q").awaitResult()
        val msgs = provider.recordedRequests.single().messages
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
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = null, toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "done: hello"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = mem, maxRounds = 20, maxIterations = 5)
        val result = agent.run("hi").awaitResult()
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
        val provider = FakeLlmProvider(
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        agent.run("hi").awaitResult()
        assertEquals(2, echo.invocations.size)
    }

    @Test
    fun `tool throwing returns isError result without aborting agent`() = runTest {
        val failingTool = object : Tool {
            override val name = "boom"
            override val description = "always fails"
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
                throw RuntimeException("kaboom")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "boom", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "recovered"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(failingTool), memory = mem, maxRounds = 20, maxIterations = 5)
        val result = agent.run("hi").awaitResult()
        assertEquals("recovered", result.message.content)
        val toolResult = mem.history().filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("kaboom"))
    }

    @Test
    fun `tool not found returns isError result`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "missing", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = mem, maxRounds = 20, maxIterations = 5)
        agent.run("hi").awaitResult()
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
        val provider = FakeLlmProvider(nonStreamResponses = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 2)
        val events = agent.run("hi").toList()
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
            override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
                throw kotlinx.coroutines.CancellationException("cancelled inside tool")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "wait", JsonNull))),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(cancellingTool), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            agent.run("hi").toList()
        }
    }

    @Test
    fun `runStream emits TextDelta and Final for plain answer`() = runTest {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hel"),
                    StreamEvent.ContentDelta("lo"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream("hi").toList()
        val texts = events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hel", "lo"), texts)
        val finals = events.filterIsInstance<AgentEvent.Final>()
        assertEquals(1, finals.size)
        assertEquals("hello", finals.single().result.message.content)
    }

    @Test
    fun `runStream handles tool call cycle`() = runTest {
        val echo = EchoTool()
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ToolCallStart(id = "c1", name = "echo"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "\"x\"}"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    StreamEvent.ContentDelta("done"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream("hi").toList()
        assertTrue(events.any { it is AgentEvent.ToolCallStart && it.toolName == "echo" })
        assertTrue(events.any { it is AgentEvent.ToolCallEnd })
        assertTrue(events.any { it is AgentEvent.Final })
        assertEquals(1, echo.invocations.size)
    }

    @Test
    fun `ToolCallStarted event is emitted before tool invocation`() = runTest {
        // 验证事件时序:Started 必须�?invokeTool 之前发出,Finished 之后
        val echo = EchoTool()
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ToolCallStart(id = "c1", name = "echo"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    StreamEvent.ContentDelta("done"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream("hi").toList()

        val startedIdx = events.indexOfFirst { it is AgentEvent.ToolCallStart }
        val finishedIdx = events.indexOfFirst { it is AgentEvent.ToolCallEnd }
        assertTrue(startedIdx >= 0, "ToolCallStarted must be emitted")
        assertTrue(finishedIdx >= 0, "ToolCallFinished must be emitted")
        assertTrue(startedIdx < finishedIdx, "ToolCallStarted must precede ToolCallFinished")
    }

    @Test
    fun `runStream propagates Error event as thrown cause`() = runTest {
        val boom = RuntimeException("stream failed")
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hel"),
                    StreamEvent.Error(boom),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)  // after Error: should not be reached
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream("hi").toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        // 边界处把非 AgentException 通过 wrap() 抬升;原 throwable 挂在 cause.cause
        assertSame(boom, failed.cause.cause)
    }

    @Test
    fun `runStream propagates Done usage to assistant ChatResponse`() = runTest {
        val expectedUsage = Usage(promptTokens = 12, completionTokens = 7, totalTokens = 19)
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hi"),
                    StreamEvent.Done(usage = expectedUsage, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream("ping").toList()
        val final = events.filterIsInstance<AgentEvent.Final>().single()
        assertEquals(expectedUsage, final.result.usage)
    }

    @Test
    fun `runStream exhausts max iterations`() = runTest {
        val toolResp = listOf(
            StreamEvent.ToolCallStart(id = "c", name = "echo"),
            StreamEvent.ToolCallDelta(id = "c", name = null, argumentsDelta = "{\"text\":\"x\"}"),
            StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
        )
        val provider = FakeLlmProvider(streamScripts = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 2)
        val events = agent.runStream("hi").toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val ex = failed.cause as AgentException.MaxIterations
        assertEquals(2, ex.max)
    }
}
