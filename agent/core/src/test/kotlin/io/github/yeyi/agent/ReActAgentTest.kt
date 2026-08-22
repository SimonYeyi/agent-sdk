package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.Role
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
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
            persona = Persona("you are helpful"), llmProvider = provider, toolRegistry = registryOf(), memory = memory, modalityAdapter = DefaultModalityAdapter(memory.mediaArchive), maxRounds = 20, maxIterations = 5
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
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
        val agent = ReActAgent(persona = Persona("ROLE"), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        agent.run(AgentQuery.text("q")).awaitResult()
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = mem, modalityAdapter = DefaultModalityAdapter(mem.mediaArchive), maxRounds = 20, maxIterations = 5)
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("done: hello", result.message.content)
        assertEquals(2, result.iterations)
        assertEquals(1, result.toolCalls.size)
        assertEquals("echo", result.toolCalls[0].toolName)
        assertEquals(1, echo.invocations.size)
        val h = mem.history()
        assertEquals(4, h.size)
        assertEquals(Role.Tool, h[2].role)
        assertEquals("hello", ((h[2] as ChatMessage.ToolResult).parts.single() as ContentPart.Text).text)
    }

    @Test
    fun `ToolCallExplanation is emitted unconditionally before tool calls even when content is empty or null`() = runTest {
        val echo = EchoTool()
        val toolCall = ToolCall(
            id = "c1", name = "echo",
            arguments = JsonObject(mapOf("text" to JsonPrimitive("x")))
        )
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                // content = null 场景
                ChatResponse(
                    ChatMessage.Assistant(content = null, toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                // content = "" 场景
                ChatResponse(
                    ChatMessage.Assistant(content = "", toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.run(AgentQuery.text("hi")).toList()

        val explanations = events.filterIsInstance<AgentEvent.ToolCallExplanation>()
        assertEquals(2, explanations.size, "ToolCallExplanation must emit on every tool-call round, even with null/empty content")
        assertEquals(null, explanations[0].text, "null content must be carried as null, not dropped")
        assertEquals(null, explanations[1].text, "empty content must be normalized to null, not dropped")

        // 顺序保证: ToolCallExplanation 始终在 ToolCallStart 之前
        val explanationsIdx = events.indices.filter { events[it] is AgentEvent.ToolCallExplanation }
        val startsIdx = events.indices.filter { events[it] is AgentEvent.ToolCallStart }
        assertTrue(explanationsIdx.size == startsIdx.size && startsIdx.all { it > 0 } && explanationsIdx.withIndex().all { (i, ei) -> ei < startsIdx[i] })
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        agent.run(AgentQuery.text("hi")).awaitResult()
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(failingTool), memory = mem, modalityAdapter = DefaultModalityAdapter(mem.mediaArchive), maxRounds = 20, maxIterations = 5)
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("recovered", result.message.content)
        val toolResult = mem.history().filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.parts.any { it is ContentPart.Text && it.text.contains("kaboom") })
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = mem, modalityAdapter = DefaultModalityAdapter(mem.mediaArchive), maxRounds = 20, maxIterations = 5)
        agent.run(AgentQuery.text("hi")).awaitResult()
        val toolResult = mem.history().filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.parts.any { it is ContentPart.Text && it.text.contains("missing") })
        assertTrue(toolResult.parts.any { it is ContentPart.Text && it.text.contains("echo") })
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 2)
        val events = agent.run(AgentQuery.text("hi")).toList()
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
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(cancellingTool), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            agent.run(AgentQuery.text("hi")).toList()
        }
    }

    @Test
    fun `runStream emits TextDelta and Final for plain answer`() = runTest {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ContentDelta("hel"),
                    ChatResponseEvent.ContentDelta("lo"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream(AgentQuery.text("hi")).toList()
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
                    ChatResponseEvent.ToolCallStart(id = "c1", name = "echo"),
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":"),
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "\"x\"}"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    ChatResponseEvent.ContentDelta("done"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream(AgentQuery.text("hi")).toList()
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
                    ChatResponseEvent.ToolCallStart(id = "c1", name = "echo"),
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    ChatResponseEvent.ContentDelta("done"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream(AgentQuery.text("hi")).toList()

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
                    ChatResponseEvent.ContentDelta("hel"),
                    ChatResponseEvent.Error(boom),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)  // after Error: should not be reached
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream(AgentQuery.text("hi")).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        // Failed 直接携带原始 Throwable,不做任何包装,因此 cause 即 boom 本体。
        assertSame(boom, failed.cause)
    }

    @Test
    fun `runStream propagates Done usage to assistant ChatResponse`() = runTest {
        val expectedUsage = Usage(promptTokens = 12, completionTokens = 7, totalTokens = 19)
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ContentDelta("hi"),
                    ChatResponseEvent.Done(usage = expectedUsage, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 5)
        val events = agent.runStream(AgentQuery.text("ping")).toList()
        val final = events.filterIsInstance<AgentEvent.Final>().single()
        assertEquals(expectedUsage, final.result.usage)
    }

    @Test
    fun `runStream exhausts max iterations`() = runTest {
        val toolResp = listOf(
            ChatResponseEvent.ToolCallStart(id = "c", name = "echo"),
            ChatResponseEvent.ToolCallDelta(id = "c", name = null, argumentsDelta = "{\"text\":\"x\"}"),
            ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
        )
        val provider = FakeLlmProvider(streamScripts = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive), maxRounds = 20, maxIterations = 2)
        val events = agent.runStream(AgentQuery.text("hi")).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val ex = failed.cause as AgentException.MaxIterations
        assertEquals(2, ex.max)
    }

    @Test
    fun `synthetic User from adaptModality keeps media while old-round User media gets placeholdered`() = runTest {
        val toolCall = ToolCall(id = "c1", name = "image_tool", arguments = JsonNull)
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = null, toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
            )
        )
        val imageTool = object : Tool {
            override val name = "image_tool"
            override val description = "returns text + image"
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolContext) =
                ToolExecutionResult.success(
                    content = "result text",
                    parts = listOf(ContentPart.Image(MediaSource.Http("https://example.com/cat.jpg")))
                )
        }
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(imageTool),
            memory = InMemoryMemory(),
            modalityAdapter = DefaultModalityAdapter(InMemoryMemory().mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )
        agent.run(
            AgentQuery(
                listOf(
                    ContentPart.Text("see this:"),
                    ContentPart.Image(MediaSource.Http("https://example.com/dog.jpg"))
                )
            )
        ).awaitResult()

        // recordedRequests[1] = iter 2 时的 buildRequest:在工具执行后发起。
        // 期望布局: [System, 末轮User(原图), Assistant(toolCalls), ToolResult(text-only), 合成User(原图)]
        val msgs = provider.recordedRequests[1].messages
        assertEquals(Role.System, msgs[0].role)
        assertEquals(Role.User, msgs[1].role)
        assertEquals(Role.Assistant, msgs[2].role)
        assertEquals(Role.Tool, msgs[3].role)
        assertEquals(Role.User, msgs[4].role)

        // 末轮 User(原图) → 保留原图不转占位
        val lastRoundUser = msgs[1] as ChatMessage.User
        assertTrue(
            lastRoundUser.parts.any { it is ContentPart.Image },
            "last-round User's Image should be preserved, got: ${lastRoundUser.parts}"
        )

        // 合成 User(来自 adaptModality)→ 原图透传
        val syntheticUser = msgs[4] as ChatMessage.User
        assertTrue(
            syntheticUser.parts.any { it is ContentPart.Image },
            "synthetic User's Image should pass through unchanged, got: ${syntheticUser.parts}"
        )
    }
}
