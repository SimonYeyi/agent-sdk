package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.Role
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReActAgentTest {

    /** 测试辅助：老风格 add(ChatMessage) 自动包装为 [MemoryEntry]。 */
    private suspend fun Memory.add(message: ChatMessage) {
        add(MemoryEntry(message))
    }

    @Test
    fun `single turn without tool call returns assistant message`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hello"), finishReason = FinishReason.Stop)
            )
        )
        val memory = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona("you are helpful"), llmProvider = provider, toolRegistry = registryOf(), memory = memory, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("hello", result.message.content)
        assertEquals(1, result.iterations)
        assertEquals(0, result.iterationRecords.size)
        val h = memory.history().map { it.message }
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
        val agent = ReActAgent(id = "test", persona = AgentPersona("ROLE"), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("done: hello", result.message.content)
        assertEquals(2, result.iterations)
        assertEquals(1, result.iterationRecords.size)
        assertEquals("echo", result.iterationRecords[0].calls.single().toolName)
        assertEquals(1, echo.invocations.size)
        val h = mem.history().map { it.message }
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals(2, echo.invocations.size)
    }

    @Test
    fun `tool throwing returns isError result without aborting agent`() = runTest {
        val failingTool = object : Tool {
            override val name = "boom"
            override val description = "always fails"
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(failingTool), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("recovered", result.message.content)
        val toolResult = mem.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>().single()
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        agent.run(AgentQuery.text("hi")).awaitResult()
        val toolResult = mem.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>().single()
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 2, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.run(AgentQuery.text("hi")).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val ex = failed.cause as AgentException.MaxIterations
        assertEquals(2, ex.max)
    }

    @Test
    fun `cancellation inside tool closes open toolCalls with cancelled marker`() = runTest {
        val cancellingTool = object : Tool {
            override val name = "wait"
            override val description = ""
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
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
        val mem = InMemoryMemory()
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(cancellingTool), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.run(AgentQuery.text("hi")).toList()
        // 取消以 Canceled 终局事件收尾，不再向收集方抛出
        assertTrue(events.last() is AgentEvent.Canceled, "cancel must end with Canceled: $events")
        // catch 块通过 NonCancellable 写入的闭合 ToolResult 应该存在,且文本为 cancelled 标记。
        val results = mem.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(1, results.size, "one orphan toolCall must be closed on cancellation")
        val tr = results.single()
        assertEquals("c1", tr.toolCallId)
        assertEquals(true, tr.isError)
        val text = tr.parts.filterIsInstance<ContentPart.Text>().single().text
        assertEquals(io.github.yeyi.agent.memory.RepairedMemory.CANCELLED, text)
    }

    @Test
    fun `cancellation mid-batch closes only the orphans, keeps completed results`() = runTest {
        // 模拟:Assistant 吐出 A、B 两个 toolCall,A 执行完,B 执行中取消
        // 用一个挂起工具让 A 返回后,B 在执行时取消
        val completedTool = object : Tool {
            override val name = "ok"
            override val description = ""
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext) =
                ToolExecutionResult.success("done")
        }
        val blockingTool = object : Tool {
            override val name = "block"
            override val description = ""
            override val parametersSchema = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
                throw kotlinx.coroutines.CancellationException("cancelled in block")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(
                        ToolCall("c1", "ok", JsonNull),
                        ToolCall("c2", "block", JsonNull),
                    )),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(completedTool, blockingTool), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.run(AgentQuery.text("hi")).toList()
        assertTrue(events.last() is AgentEvent.Canceled, "cancel must end with Canceled: $events")
        val results = mem.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(2, results.size)
        val c1 = results.single { it.toolCallId == "c1" }
        assertEquals(false, c1.isError, "c1 completed normally before cancellation")
        val c2 = results.single { it.toolCallId == "c2" }
        assertEquals(true, c2.isError)
        assertEquals(
            io.github.yeyi.agent.memory.RepairedMemory.CANCELLED,
            c2.parts.filterIsInstance<ContentPart.Text>().single().text
        )
    }

    @Test
    fun `next run repairs crashed history with unknown-reason marker before adding new User`() = runTest {
        // 模拟:上次 run 留下尾部孤儿(类似进程崩溃的残留历史),
        // 新 run 启动时应在 add(User) 之前自动闭合孤儿,使新 run 能正常拼 request。
        val mem = InMemoryMemory()
        mem.add(ChatMessage.User(listOf(ContentPart.Text("q1"))))
        mem.add(ChatMessage.Assistant(toolCalls = listOf(ToolCall("c1", "search", JsonNull))))
        // 注意:不写 c1 的 ToolResult,模拟崩溃

        val echo = EchoTool()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                // 新 run 第一次 LLM 调用应该看到闭合后的合法历史并给出最终回答
                ChatResponse(ChatMessage.Assistant(content = "fixed"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        agent.run(AgentQuery.text("q2")).awaitResult()

        val h = mem.history().map { it.message }
        // 结构应为: User(q1), Assistant(c1), ToolResult(c1, [crashed: ...]), User(q2), Assistant("fixed")
        val crashMarker = h.filterIsInstance<ChatMessage.ToolResult>().single { it.toolCallId == "c1" }
        assertEquals(true, crashMarker.isError)
        assertEquals(
            io.github.yeyi.agent.memory.RepairedMemory.CRASHED,
            crashMarker.parts.filterIsInstance<ContentPart.Text>().single().text
        )
        // crash marker 必须在 User(q2) 之前
        val markerIdx = h.indexOf(crashMarker)
        val userQ2Idx = h.indexOfFirst { it is ChatMessage.User && it.parts.any { p -> p is ContentPart.Text && p.text == "q2" } }
        assertTrue(markerIdx < userQ2Idx, "crash repair must happen before new User is added")

        // 传给 LLM 的请求里历史应该是合法的(c1 已闭合),否则 provider 会因非法 tool_call 拒绝 ——
        // 这里只要 run 成功完成就说明修复生效了。
    }

    @Test
    fun `clean run does not insert repair markers into history`() = runTest {
        val mem = InMemoryMemory()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hi"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(), memory = mem, modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        agent.run(AgentQuery.text("q1")).awaitResult()

        val allText = mem.history().map { it.message }.flatMap { msg ->
            when (msg) {
                is ChatMessage.User -> msg.parts.filterIsInstance<ContentPart.Text>().map { it.text }
                is ChatMessage.Assistant -> listOfNotNull(msg.content)
                is ChatMessage.ToolResult -> msg.parts.filterIsInstance<ContentPart.Text>().map { it.text }
                is ChatMessage.System -> listOf(msg.content)
            }
        }
        val markers = setOf(
            io.github.yeyi.agent.memory.RepairedMemory.CRASHED,
            io.github.yeyi.agent.memory.RepairedMemory.CANCELLED,
        )
        assertTrue(
            allText.none { it in markers },
            "clean run must not produce repair markers, got: $allText"
        )
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
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
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = "echo", argumentsDelta = ""),
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.runStream(AgentQuery.text("hi")).toList()
        assertTrue(events.any { it is AgentEvent.ToolCallStart && it.toolCall.name == "echo" })
        assertTrue(events.any { it is AgentEvent.ToolCallEnd })
        assertTrue(events.any { it is AgentEvent.Final })
        assertEquals(1, echo.invocations.size)
    }

    @Test
    fun `first delta without name fails the run explicitly`() = runTest {
        // 契约:首个 delta 必须带 name 标记开始;缺失属 provider 违约,显式 Failed 而非静默空串
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = ""),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.runStream(AgentQuery.text("hi")).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.cause is IllegalArgumentException)
        assertTrue(failed.cause.message!!.contains("must carry a non-null name"))
    }

    @Test
    fun `ToolCallStarted event is emitted before tool invocation`() = runTest {
        // 验证事件时序:Started 必须�?invokeTool 之前发出,Finished 之后
        val echo = EchoTool()
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = "echo", argumentsDelta = ""),
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    ChatResponseEvent.ContentDelta("done"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(echo), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
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
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 5, dispatcher = StandardTestDispatcher(testScheduler))
        val events = agent.runStream(AgentQuery.text("ping")).toList()
        val final = events.filterIsInstance<AgentEvent.Final>().single()
        assertEquals(expectedUsage, final.result.usage)
    }

    @Test
    fun `runStream exhausts max iterations`() = runTest {
        val toolResp = listOf(
            ChatResponseEvent.ToolCallDelta(id = "c", name = "echo", argumentsDelta = ""),
            ChatResponseEvent.ToolCallDelta(id = "c", name = null, argumentsDelta = "{\"text\":\"x\"}"),
            ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
        )
        val provider = FakeLlmProvider(streamScripts = listOf(toolResp, toolResp, toolResp))
        val agent = ReActAgent(id = "test", persona = AgentPersona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(null), maxRounds = 20, maxIterations = 2, dispatcher = StandardTestDispatcher(testScheduler))
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
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext) =
                ToolExecutionResult.success(
                    content = "result text",
                    parts = listOf(ContentPart.Image(MediaSource.Http("https://example.com/cat.jpg")))
                )
        }
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(imageTool),
            memory = InMemoryMemory(),
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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

    /** 测试辅助:可终结工具。执行后立即返回,裁定可终结后不再触发下一轮推理。 */
    private fun asyncTool(name: String = "async_echo"): Tool = object : Tool, FinalizableTool {
        override val name = name
        override val description = "dispatches async"
        override val parametersSchema = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("dispatched async")
    }

    @Test
    fun `single AsyncTool call short-circuits round without triggering another LLM call`() = runTest {
        // 异步派发型工具:本轮无可同步等待的下一步结果,ReAct 不应再触发下一轮推理。
        val toolCall = ToolCall(id = "c1", name = "async_echo", arguments = JsonNull)
        // 只提供一个响应:若短路失效,FakeLlmProvider 会在第二次 chat() 时越界 check 直接失败
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "正在派发,请稍等", toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(asyncTool()),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val events = agent.run(AgentQuery.text("hi")).toList()

        // 短路成立:LLM 仅调用一次
        assertEquals(1, provider.recordedRequests.size)
        assertTrue(events.first() is AgentEvent.Initial, "must start with Initial: $events")
        assertTrue(events.last() is AgentEvent.Final, "must end with Final: $events")
        assertTrue(events.none { it is AgentEvent.Failed }, "no Failed on short-circuit round")

        // 事件尾序: ToolCallExplanation → ToolCallStart → ToolCallEnd → Final
        val tail = events.takeLast(4)
        assertTrue(tail[0] is AgentEvent.ToolCallExplanation)
        assertTrue(tail[1] is AgentEvent.ToolCallStart)
        assertTrue(tail[2] is AgentEvent.ToolCallEnd)
        assertTrue(tail[3] is AgentEvent.Final)

        // 可终结短路轮：Final 为模拟模型空返回的空响应（content=null），
        // 前端识别 content 为 null 的 Final 不渲染，避免过渡语归位造成 UI 跳动。
        assertNull(
            events.filterIsInstance<AgentEvent.Final>().single().result.message.content,
            "可终结短路轮的 Final 必须为空响应，过渡语不再由 Final 携带"
        )
        // 记忆顺序与运行时事件流对齐：
        // User(query) → Assistant(过渡语 + toolCalls) → ToolResult → Assistant(空响应终结)
        // 过渡语随声明消息一次性写入（无重复），空响应终结消息渲染时被前端跳过。
        val memMessages = mem.history().map { it.message }
        assertEquals(4, memMessages.size, "query + 声明(含过渡语) + 工具结果 + 空响应终结，共 4 条")
        assertTrue(memMessages[1] is ChatMessage.Assistant, "第 2 条为工具声明消息（携带过渡语）")
        val declaration = memMessages[1] as ChatMessage.Assistant
        assertEquals("正在派发,请稍等", declaration.content, "过渡语随声明消息写入，Final 不再重复携带")
        assertEquals(listOf(toolCall), declaration.toolCalls)
        assertTrue(memMessages[2] is ChatMessage.ToolResult, "第 3 条为工具结果")
        assertEquals("c1", (memMessages[2] as ChatMessage.ToolResult).toolCallId)
        assertTrue(memMessages[3] is ChatMessage.Assistant, "第 4 条为空响应终结消息")
        val terminator = memMessages[3] as ChatMessage.Assistant
        assertNull(terminator.content, "终结消息模拟模型空返回，无文本")
        assertTrue(terminator.toolCalls.isEmpty(), "终结消息不携带 toolCalls")
    }

    @Test
    fun `mixing AsyncTool with a regular tool does not short-circuit`() = runTest {
        val echo = EchoTool()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(
                        content = "开始处理",
                        toolCalls = listOf(
                            ToolCall("c1", "async_echo", JsonNull),
                            ToolCall("c2", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
                        )
                    ),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "final answer"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(asyncTool(), echo),
            memory = InMemoryMemory(),
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()

        // 混调普通工具 → 不短路,LLM 再跑一轮给出最终答复
        assertEquals(2, provider.recordedRequests.size)
        assertEquals(2, result.iterations)
        assertEquals("final answer", result.message.content)
        assertEquals(
            2,
            result.iterationRecords.sumOf { it.calls.size },
        )
        assertEquals(1, echo.invocations.size)
    }

    @Test
    fun `finalizable tool refusing termination after failure does not short-circuit so LLM retries`() = runTest {
        // 短路裁决权完全在工具：ReActAgent 把本轮执行结果原样回传给 finalizable()，
        // 工具自行结合成败裁定。本例工具对错误回执拒绝终结（finalizable() 返回 false）
        // → 不短路 → 模型下一轮感知失败回执并重试。这正是产卡工具不适合挂 AsyncTool
        // 短路的原因——同步工具可能异常，异常后应由工具结合错误回执拒绝终结。
        val flaky = object : Tool, FinalizableTool {
            override val name = "flaky_card"
            override val description = "a finalizable tool that refuses termination after failure"
            override val parametersSchema = ToolParameters.Empty
            override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean = !result.isError
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.error("card generation failed")
        }
        val toolCall = ToolCall(id = "c1", name = "flaky_card", arguments = JsonNull)
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "正在生成卡片", toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "卡片生成失败,已为你重试"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(flaky),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val events = agent.run(AgentQuery.text("hi")).toList()

        // 不短路:LLM 被调用两次,第二轮带着失败回执重试
        assertEquals(2, provider.recordedRequests.size)
        val final = events.filterIsInstance<AgentEvent.Final>().single()
        assertEquals("卡片生成失败,已为你重试", final.result.message.content)
        assertEquals(2, final.result.iterations)
        // 失败回执已写入记忆（isError=true），模型第二轮能看到并重试
        val memMessages = mem.history().map { it.message }
        val toolResult = memMessages.filterIsInstance<ChatMessage.ToolResult>().single()
        assertTrue(toolResult.isError, "失败回执必须以 isError 写入记忆")
    }

    @Test
    fun `finalizable tool declaring non-finalizable does not short-circuit`() = runTest {
        // finalizable(arguments, result) = false：工具对任意结果自判本轮不可终结
        // （如非原子产卡，产卡后仍需模型复核/续写）。
        val reviewTool = object : Tool, FinalizableTool {
            override val name = "review_card"
            override val description = "drafts a card pending review"
            override val parametersSchema = ToolParameters.Empty
            override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean = false
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.success("card draft ready")
        }
        val toolCall = ToolCall(id = "c1", name = "review_card", arguments = JsonNull)
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "生成卡片草稿", toolCalls = listOf(toolCall)),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "草稿已生成,请确认"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(reviewTool),
            memory = InMemoryMemory(),
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val events = agent.run(AgentQuery.text("hi")).toList()

        // 不短路:LLM 被调用两次,Final 携带正文（非空响应）
        assertEquals(2, provider.recordedRequests.size)
        val final = events.filterIsInstance<AgentEvent.Final>().single()
        assertEquals("草稿已生成,请确认", final.result.message.content)
        assertEquals(2, final.result.iterations)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `steer injected at short-circuit point preempts Final and forces another round`() =
        runTest(UnconfinedTestDispatcher()) {
            // iter 1: LLM 调 AsyncTool → 工具执行 → 短路点发现在途 steer → 放弃终结
            // iter 2: 带着 steer 指令再跑一轮 → 正常 Final
            val provider = ControllableLlmProvider(
                listOf(
                    ChatResponse(
                        ChatMessage.Assistant(content = null, toolCalls = listOf(ToolCall("c1", "async_echo", JsonNull))),
                        finishReason = FinishReason.ToolCalls
                    ),
                    ChatResponse(ChatMessage.Assistant(content = "steered final"), finishReason = FinishReason.Stop)
                )
            )
            val mem = InMemoryMemory()
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(asyncTool()),
                memory = mem,
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = StandardTestDispatcher(testScheduler)
            )

            backgroundScope.launch {
                agent.run(AgentQuery.text("hi")).collect { }
            }
            advanceUntilIdle()

            // 在工具执行 / 短路裁决前注入 steer
            assertTrue(agent.steer(AgentQuery.text("switch to plan B")))

            // 释放 iter 1 → 工具执行 → 短路点发现 steer → 放弃终结 → iter 2
            provider.completeNext()
            advanceUntilIdle()

            // 释放 iter 2 → Final
            provider.completeNext()
            advanceUntilIdle()

            // 未短路:LLM 被调用两次
            assertEquals(2, provider.recordedRequests.size)
            // 第二轮 request 应携带 steer 指令
            val secondRequest = provider.recordedRequests[1]
            val steerInRequest = secondRequest.messages
                .filterIsInstance<ChatMessage.User>()
                .flatMap { it.parts }
                .filterIsInstance<ContentPart.Text>()
                .any { it.text.contains("switch to plan B") }
            assertTrue(steerInRequest, "steer instruction should appear in the second LLM request")
            // Final 内容为第二轮回答,而非第一轮的过渡语
            val finalMsg = mem.history().map { it.message }.filterIsInstance<ChatMessage.Assistant>().last()
            assertEquals("steered final", finalMsg.content)
        }

    /**
     * 回归：终局顺序是"先还槽、后发终局事件" —— 收集方收到终局事件的那一刻，
     * 槽位必须已经归还，收集方可以立刻开下一轮。
     *
     * 本用例锁的是**对外承诺**：收到终局事件 ⟹ 槽位已还 ⟹ 紧接着 run() 必定起得来。
     *
     * 它验的不是 core 内部「还槽先于发事件」这句语句顺序 —— 终局事件的**送达**
     * 发生在 emit 返回之后（终局流要靠 transformWhile 收尾，而 transformWhile 的
     * flow{ coroutineScope{…} } 把收集方的恢复派发到了 emit 之外），故收集方回调
     * 永远看不到「槽位还占着」那个中间态：把 core 里的顺序倒过来，本用例照样通过。
     * 内部顺序靠代码审读保证（core 侧注释已写明），黑盒测不出来。
     * 这与「混流」是同一类：理论窗口，靠调度延迟兜底 —— 顺序的价值在于让保证
     * 无条件成立，而不是修一个可观察的缺陷。
     *
     * 收尾要覆盖真实时序：provider 的第一次调用挂在闸门上，轮次停在 LLM 调用里，
     * 等收集方订阅完毕再放行 —— 终局事件是"轮次还活着时"发出的，不是回放里的旧事件。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `终局事件送达时槽位已还 —— 收到 Final 立即开新轮必定成功`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val provider = object : LlmProvider {
                override val name = "gated"
                override suspend fun chat(request: ChatRequest): ChatResponse {
                    calls++
                    if (calls == 1) {
                        gate.await() // 轮次停在 LLM 调用里，等收集方订阅完毕
                        return ChatResponse(ChatMessage.Assistant(content = "one"), finishReason = FinishReason.Stop)
                    }
                    return ChatResponse(ChatMessage.Assistant(content = "two"), finishReason = FinishReason.Stop)
                }
                override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
            }
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(),
                memory = InMemoryMemory(),
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = UnconfinedTestDispatcher(testScheduler)
            )

            val flow = agent.run(AgentQuery.text("one")) // 轮次开跑，停在闸门上

            var newRoundFailure: Throwable? = null
            val collector = launch {
                flow.collect { event ->
                    if (event is AgentEvent.Final) {
                        // 此刻槽位已还：这行不会抛并发拒绝
                        newRoundFailure = runCatching { agent.run(AgentQuery.text("two")) }.exceptionOrNull()
                    }
                }
            }
            gate.complete(Unit) // 放行 → 终局 → 事件送达收集方
            collector.join()

            assertNull(newRoundFailure, "收到终局事件后应能立即开新轮")
            assertEquals(2, calls, "新轮确实跑起来了")
        }

    /**
     * 回归：取消路径的终局顺序同样是"先还槽、后发终局事件" ——
     * 收集方收到 Canceled 的那一刻槽位必须已经归还，可以立刻开下一轮。
     *
     * 取消是 demo 的 Cancel 按钮直接走的路径，契约与 Final 路径同型：
     * 收到 Canceled ⟹ 槽位已还 ⟹ run() 必定起得来。
     * 本用例锁的是对外承诺（内部顺序为何黑盒测不出，见 Final 用例的说明）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `终局事件送达时槽位已还 —— 收到 Canceled 立即开新轮必定成功`() =
        runTest(UnconfinedTestDispatcher()) {
            // provider 永不返回：轮次停在 LLM 调用上，直到被 cancel 打断。
            val provider = object : LlmProvider {
                override val name = "hanging"
                override suspend fun chat(request: ChatRequest): ChatResponse = awaitCancellation()
                override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
            }
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(),
                memory = InMemoryMemory(),
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = UnconfinedTestDispatcher(testScheduler)
            )

            val flow = agent.run(AgentQuery.text("hi")) // 轮次开跑，停在 LLM 调用里

            var newRoundFailure: Throwable? = null
            val collector = launch {
                flow.collect { event ->
                    if (event is AgentEvent.Canceled) {
                        // 此刻槽位已还：这行不会抛并发拒绝
                        newRoundFailure = runCatching { agent.run(AgentQuery.text("again")) }.exceptionOrNull()
                    }
                }
            }
            agent.cancel("boom") // 打断 → 还槽 → Canceled 送达收集方
            collector.join()

            assertNull(newRoundFailure, "收到 Canceled 后应能立即开新轮")
        }

    /**
     * 回归：失败路径的终局顺序同样是"先还槽、后发终局事件" ——
     * 收集方收到 Failed 的那一刻槽位必须已经归还，可以立刻开下一轮。
     *
     * 本用例锁的是对外承诺（内部顺序为何黑盒测不出，见 Final 用例的说明）；
     * 顺带钉住 Failed 携带原始异常 —— 失败被静默吞成 Canceled 是这条路径上更大的坑。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `终局事件送达时槽位已还 —— 收到 Failed 立即开新轮必定成功`() =
        runTest(UnconfinedTestDispatcher()) {
            val boom = IllegalStateException("llm down")
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val provider = object : LlmProvider {
                override val name = "gated-failing"
                override suspend fun chat(request: ChatRequest): ChatResponse {
                    calls++
                    if (calls == 1) {
                        gate.await() // 轮次停在 LLM 调用里，等收集方订阅完毕
                        throw boom
                    }
                    return ChatResponse(ChatMessage.Assistant(content = "second"), finishReason = FinishReason.Stop)
                }
                override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
            }
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(),
                memory = InMemoryMemory(),
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = UnconfinedTestDispatcher(testScheduler)
            )

            val flow = agent.run(AgentQuery.text("hi")) // 轮次开跑，停在闸门上

            var failedCause: Throwable? = null
            var newRoundFailure: Throwable? = null
            val collector = launch {
                flow.collect { event ->
                    if (event is AgentEvent.Failed) {
                        failedCause = event.cause
                        // 此刻槽位已还：这行不会抛并发拒绝
                        newRoundFailure = runCatching { agent.run(AgentQuery.text("again")) }.exceptionOrNull()
                    }
                }
            }
            gate.complete(Unit) // 放行 → 失败 → 还槽 → Failed 送达收集方
            collector.join()

            assertSame(boom, failedCause, "Failed 须携带原始异常")
            assertNull(newRoundFailure, "收到 Failed 后应能立即开新轮")
            assertEquals(2, calls, "新轮确实跑起来了")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `执行体尚未开跑就被取消 —— 兜底补发 Canceled 终局事件`() =
        runTest(StandardTestDispatcher()) {
            val provider = FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(ChatMessage.Assistant(content = "hi"), finishReason = FinishReason.Stop)
                )
            )
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(),
                memory = InMemoryMemory(),
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = StandardTestDispatcher(testScheduler)
            )

            val flow = agent.run(AgentQuery.text("hello"))
            // 执行体已 start() 派发、但还没被调度器取走：体内的 try/catch/finally 都不会跑，
            // 只能靠 invokeOnCompletion 兜底补发终局。
            agent.cancel("boom")
            advanceUntilIdle()

            val events = flow.toList()
            assertTrue(
                events.any { it is AgentEvent.Canceled },
                "执行体未开跑即被打断时，仍须以 Canceled 终局事件收尾，实际收到：$events"
            )
            // 兜底路径同样要先还槽：收集方拿到终局事件即可开新轮
            agent.run(AgentQuery.text("again")).toList()
            assertEquals(1, provider.recordedRequests.size, "第一轮从未开跑，第二轮才真正调用了 LLM")
        }

    @Test
    fun `终局处理自身失败也不丢终局事件 —— 仍以 Failed 收尾`() = runTest {
        // LLM 无脚本 → chat() 直接抛；写错误记忆时记忆再抛一次：
        // 终局处理链自身连环失败，终局事件仍须送达（契约：必有终局事件）。
        val provider = FakeLlmProvider()
        val memory = object : Memory {
            override suspend fun add(entry: MemoryEntry) {
                if (entry.label == "error") throw IllegalStateException("memory down")
            }

            override suspend fun history(): List<MemoryEntry> = emptyList()
            override suspend fun rebuild(entries: List<MemoryEntry>) = Unit
        }
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = memory,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val events = agent.run(AgentQuery.text("hello")).toList()
        assertTrue(
            events.any { it is AgentEvent.Failed },
            "终局处理自身失败也须以 Failed 终局事件收尾，实际收到：$events"
        )
    }
}
