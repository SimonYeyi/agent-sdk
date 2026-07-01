package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicSubagentTest {

    private class NoopLlm : LlmProvider {
        override val name = "noop"
        override suspend fun chat(request: ChatRequest) =
            throw UnsupportedOperationException()
        override fun chatStream(request: ChatRequest) =
            throw UnsupportedOperationException()
    }

    private class StubLlmProvider(
        private val responses: List<ChatResponse> = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    ) : LlmProvider {
        override val name: String = "stub"
        val chatRequests: MutableList<ChatRequest> = mutableListOf()
        private var index = 0

        override suspend fun chat(request: ChatRequest): ChatResponse {
            chatRequests += request
            check(index < responses.size) {
                "StubLlmProvider: chat() called ${index + 1} times, but only ${responses.size} responses scripted"
            }
            return responses[index++]
        }

        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
    }

    private fun chatResponse(content: String): ChatResponse =
        ChatResponse(ChatMessage.Assistant(content = content), finishReason = FinishReason.Stop)

    private fun stubAgentContext(
        llm: LlmProvider,
        maxIterations: Int = 100,
        maxRounds: Int = 20,
        tools: List<Tool> = emptyList(),
        memory: Memory = InMemoryMemory(),
    ): AgentContext = AgentContext(
        persona = Persona("main"),
        maxIterations = maxIterations,
        currentIteration = 1,
        memory = memory,
        llmProvider = llm,
        tools = tools,
        maxRounds = maxRounds,
    )

    private class StubTool(private val toolName: String) : Tool {
        override val name: String get() = toolName
        override val description: String = "stub $toolName"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("")
    }

    private fun toolCtx(ac: AgentContext): ToolContext =
        ToolContext(toolCallId = "tc-1", agentContext = ac)

    @Test
    fun `Task round-trips via JSON`() {
        val original = DynamicSubagentTool.Task(
            role = "You are a code reviewer",
            context = "Review the following code",
            tasks = listOf("review file A", "review file B"),
            toolList = listOf("read_file"),
        )
        val element: JsonElement = Json.encodeToJsonElement(DynamicSubagentTool.Task.serializer(), original)
        val restored = Json.decodeFromJsonElement(DynamicSubagentTool.Task.serializer(), element)
        assertEquals(original, restored)
    }

    @Test
    fun `schema declares role context tasks tool_list with required role and tasks`() {
        val tool = DynamicSubagentTool()
        val schema = (tool.parametersSchema as ToolParameters.JsonSchema).schema
        val parsed = Json.parseToJsonElement(schema) as JsonObject
        val properties = parsed["properties"] as JsonObject
        assertNotNull(properties["role"])
        assertNotNull(properties["context"])
        assertNotNull(properties["tasks"])
        assertNotNull(properties["tool_list"])
        val required = (parsed["required"] as JsonArray).map { it.toString().trim('"') }.toSet()
        assertTrue("role" in required)
        assertTrue("tasks" in required)
        assertTrue("context" !in required)
        assertTrue("tool_list" !in required)
    }

    @Test
    fun `tool name is dynamic_subagent`() {
        assertEquals("dynamic_subagent", DynamicSubagentTool().name)
    }

    @Test
    fun `subagent(dynamic=true) DSL registers the tool on AgentBuilder`() {
        val builder = AgentBuilder().apply { llmProvider(NoopLlm()) }
        builder.subagent(dynamic = true)
        // AgentBuilder.toolRegistry is private; access it via reflection so we can assert
        // the DSL actually registered a tool under the expected name.
        val field = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        val registry = field.get(builder) as ToolRegistry
        val names = registry.all().map { it.name }
        assertTrue(
            "dynamic_subagent" in names,
            "expected 'dynamic_subagent' in $names"
        )
    }

    @Test
    fun `execute rejects empty role`() = runTest {
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(StubLlmProvider()))
        val args = buildJsonObject {
            put("role", "")
            put("tasks", buildJsonArray { add(JsonPrimitive("t1")) })
        }
        val result = tool.execute(args, ctx)
        assertTrue(result.isError, "empty role must produce error result")
        assertTrue(result.content.contains("role"), "error must mention 'role'")
    }

    @Test
    fun `execute rejects empty tasks list`() = runTest {
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(StubLlmProvider()))
        val args = buildJsonObject {
            put("role", "reviewer")
            put("tasks", buildJsonArray { })
        }
        val result = tool.execute(args, ctx)
        assertTrue(result.isError)
        assertTrue(result.content.contains("tasks"))
    }

    @Test
    fun `execute with single task invokes LLM once and returns its content`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("done")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("role", "reviewer")
            put("tasks", buildJsonArray { add(JsonPrimitive("review X")) })
        }
        val result = tool.execute(args, ctx)
        assertFalse(result.isError)
        assertEquals("[1] done", result.content)
        assertEquals(1, llm.chatRequests.size)
    }

    @Test
    fun `execute runs N tasks concurrently and aggregates in order`() = runTest {
        val llm = StubLlmProvider(
            listOf(chatResponse("result-A"), chatResponse("result-B"), chatResponse("result-C"))
        )
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("role", "reviewer")
            put("tasks", buildJsonArray {
                add(JsonPrimitive("task A"))
                add(JsonPrimitive("task B"))
                add(JsonPrimitive("task C"))
            })
        }
        val result = tool.execute(args, ctx)
        assertFalse(result.isError)
        assertEquals(3, llm.chatRequests.size)
        val expected = "[1] result-A\n\n[2] result-B\n\n[3] result-C"
        assertEquals(expected, result.content)
    }

    @Test
    fun `result format uses blank line separator between tasks`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("x"), chatResponse("y")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        }
        val result = tool.execute(args, ctx)
        assertEquals("[1] x\n\n[2] y", result.content)
    }

    @Test
    fun `execute with tool_list=null inherits filtered main tools`() = runTest {
        val keep = StubTool("read_file")
        val staticSubagent = StubTool("subagent_dispatch")
        val dynamicSelf = StubTool("dynamic_subagent")
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(keep, staticSubagent, dynamicSelf)))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray { add(JsonPrimitive("t")) })
            // tool_list omitted → null
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("read_file"), visible)
    }

    @Test
    fun `execute with tool_list of names uses exactly those tools`() = runTest {
        val a = StubTool("alpha")
        val b = StubTool("beta")
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(a, b)))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray { add(JsonPrimitive("t")) })
            put("tool_list", buildJsonArray { add(JsonPrimitive("alpha")); add(JsonPrimitive("nonexistent")) })
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("alpha"), visible)  // "nonexistent" 静默忽略
    }

    @Test
    fun `execute with empty tool_list gives subagent no tools`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(StubTool("any"))))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray { add(JsonPrimitive("t")) })
            put("tool_list", buildJsonArray { })
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools
        assertTrue(visible.isEmpty(), "tool_list=[] must give subagent zero tools")
    }

    @Test
    fun `single task failure does not cancel siblings`() = runTest {
        var callCount = 0
        val llm = object : LlmProvider {
            override val name = "fail-mid"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                callCount++
                if (callCount == 2) throw IllegalStateException("simulated task 2 failure")
                return chatResponse("ok-$callCount")
            }

            override fun chatStream(request: ChatRequest) =
                flowOf(StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray {
                add(JsonPrimitive("t1"))
                add(JsonPrimitive("t2"))
                add(JsonPrimitive("t3"))
            })
        }
        val result = tool.execute(args, ctx)
        assertTrue(
            result.isError,
            "tool.execute must return error when any task fails: ${result.content}"
        )
        val parts = result.content.split("\n\n")
        assertEquals(3, parts.size, "expected exactly 3 result parts, got: ${result.content}")
        assertTrue(parts[0].startsWith("[1] ok-1"), "task 1 should succeed: ${parts[0]}")
        assertTrue(parts[1].startsWith("[2] "), "task 2 should have a result prefix: ${parts[1]}")
        assertTrue(parts[2].startsWith("[3] ok-3"), "task 3 should succeed: ${parts[2]}")
        // task 2 should NOT be a successful LLM response (since LLM threw on call 2)
        assertFalse(
            parts[1].contains("ok-"),
            "task 2 result should be error-like, not content: ${parts[1]}"
        )
    }

    @Test
    fun `execute never reads or writes main agent memory`() = runTest {
        val mainMemory = InMemoryMemory()
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, memory = mainMemory))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray { add(JsonPrimitive("a separate task")) })
        }
        tool.execute(args, ctx)
        assertTrue(mainMemory.history().isEmpty(), "main agent memory must remain untouched")
    }

    @Test
    fun `tasks run in parallel not sequentially`() = runTest {
        val slowLlm = object : LlmProvider {
            override val name = "slow"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                delay(50)
                return chatResponse("ok")
            }

            override fun chatStream(request: ChatRequest) =
                flowOf(StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(slowLlm))
        val args = buildJsonObject {
            put("role", "p")
            put("tasks", buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
                add(JsonPrimitive("c"))
            })
        }
        val start = currentTime
        tool.execute(args, ctx)
        val elapsed = currentTime - start
        assertTrue(elapsed < 120, "expected ~50ms (parallel), got ${elapsed}ms (likely sequential)")
    }
}