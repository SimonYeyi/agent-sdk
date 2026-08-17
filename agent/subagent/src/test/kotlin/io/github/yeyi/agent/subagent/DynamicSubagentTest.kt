package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
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

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
            flowOf(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
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

    private fun agentJson(role: String, context: String? = null, task: String): JsonElement =
        buildJsonObject {
            put("role", role)
            if (context != null) put("context", context)
            put("task", task)
        }

    @Test
    fun `schema declares subagents array with per-item role context task tools`() {
        val tool = DynamicSubagentTool()
        val schema = (tool.parametersSchema as ToolParameters.JsonSchema).schema
        val parsed = Json.parseToJsonElement(schema) as JsonObject
        val topRequired = (parsed["required"] as JsonArray).map { it.toString().trim('"') }.toSet()
        assertEquals(setOf("subagents"), topRequired)
        val topProps = parsed["properties"] as JsonObject
        val agents = topProps["subagents"] as JsonObject
        assertEquals(1, (agents["minItems"] as JsonPrimitive).content.toInt())
        val item = agents["items"] as JsonObject
        val itemProps = item["properties"] as JsonObject
        assertNotNull(itemProps["role"])
        assertNotNull(itemProps["context"])
        assertNotNull(itemProps["task"])
        assertNotNull(itemProps["tools"])
        val itemRequired = (item["required"] as JsonArray).map { it.toString().trim('"') }.toSet()
        assertEquals(setOf("role", "task"), itemRequired)
    }

    @Test
    fun `tool name is dynamic_subagent`() {
        assertEquals("dynamic_subagent", DynamicSubagentTool().name)
    }

    @Test
    fun `subagents(dynamic=true) DSL registers the tool for the LLM`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val a = agent {
            llmProvider(llm)
            subagents(dynamic = true)
        }
        a.run(AgentQuery.text("hi")).awaitResult()
        val toolNames = llm.chatRequests.single().tools.map { it.name }
        assertTrue(
            "dynamic_subagent" in toolNames,
            "expected 'dynamic_subagent' in $toolNames"
        )
    }

    @Test
    fun `subagents(dynamic=false) DSL registers nothing`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val a = agent {
            llmProvider(llm)
            subagents(dynamic = false)
        }
        a.run(AgentQuery.text("hi")).awaitResult()
        val toolNames = llm.chatRequests.single().tools.map { it.name }
        assertTrue(toolNames.isEmpty(), "dynamic=false must not register any tool, got $toolNames")
    }

    @Test
    fun `execute rejects empty role`() = runTest {
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(StubLlmProvider()))
        val args = buildJsonObject {
            put("subagents", buildJsonArray { add(agentJson(role = "", task = "t1")) })
        }
        val result = tool.execute(args, ctx)
        assertTrue(result.isError, "empty role must produce error result")
        assertTrue(result.parts.text.contains("role"), "error must mention 'role'")
    }

    @Test
    fun `execute rejects empty agents list`() = runTest {
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(StubLlmProvider()))
        val args = buildJsonObject {
            put("subagents", buildJsonArray { })
        }
        val result = tool.execute(args, ctx)
        assertTrue(result.isError)
        assertTrue(result.parts.text.contains("subagents"))
    }

    @Test
    fun `execute with single agent invokes LLM once and returns its content`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("done")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("subagents", buildJsonArray { add(agentJson(role = "reviewer", task = "review X")) })
        }
        val result = tool.execute(args, ctx)
        assertFalse(result.isError)
        assertEquals("[1] review X\ndone", result.parts.text)
        assertEquals(1, llm.chatRequests.size)
    }

    @Test
    fun `execute runs N agents concurrently and aggregates in order with task tags`() = runTest {
        val llm = StubLlmProvider(
            listOf(chatResponse("result-A"), chatResponse("result-B"), chatResponse("result-C"))
        )
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(agentJson(role = "code", task = "task A"))
                add(agentJson(role = "security", task = "task B"))
                add(agentJson(role = "ux", task = "task C"))
            })
        }
        val result = tool.execute(args, ctx)
        assertFalse(result.isError)
        assertEquals(3, llm.chatRequests.size)
        val expected = "[1] task A\nresult-A\n\n[2] task B\nresult-B\n\n[3] task C\nresult-C"
        assertEquals(expected, result.parts.text)
    }

    @Test
    fun `result format uses blank line separator between agents`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("x"), chatResponse("y")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(agentJson(role = "a", task = "x"))
                add(agentJson(role = "b", task = "y"))
            })
        }
        val result = tool.execute(args, ctx)
        assertEquals("[1] x\nx\n\n[2] y\ny", result.parts.text)
    }

    @Test
    fun `execute with tools=null inherits filtered main tools`() = runTest {
        val keep = StubTool("read_file")
        val staticSubagent = StubTool("subagent_dispatch")
        val dynamicSelf = StubTool("dynamic_subagent")
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(keep, staticSubagent, dynamicSelf)))
        val args = buildJsonObject {
            put("subagents", buildJsonArray { add(agentJson(role = "p", task = "t")) })
            // tools omitted → null
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("read_file"), visible)
    }

    @Test
    fun `execute with tools list uses exactly those tools`() = runTest {
        val a = StubTool("alpha")
        val b = StubTool("beta")
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(a, b)))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "p")
                    put("task", "t")
                    put("tools", buildJsonArray { add(JsonPrimitive("alpha")); add(JsonPrimitive("nonexistent")) })
                })
            })
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("alpha"), visible)  // "nonexistent" 静默忽略
    }

    @Test
    fun `execute with empty tools list gives subagent no tools`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(StubTool("any"))))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "p")
                    put("task", "t")
                    put("tools", buildJsonArray { })
                })
            })
        }
        tool.execute(args, ctx)
        val visible = llm.chatRequests.single().tools
        assertTrue(visible.isEmpty(), "tools=[] must give subagent zero tools")
    }

    @Test
    fun `heterogeneous agents each get their own context tools`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("ok-1"), chatResponse("ok-2"), chatResponse("ok-3")))
        val readOnly = StubTool("read_file")
        val writeFile = StubTool("write_file")
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, tools = listOf(readOnly, writeFile)))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "code")
                    put("task", "review A")
                    put("tools", buildJsonArray { add(JsonPrimitive("read_file")) })
                })
                add(buildJsonObject {
                    put("role", "writer")
                    put("context", "needs to persist")
                    put("task", "draft B")
                    put("tools", buildJsonArray { add(JsonPrimitive("read_file")); add(JsonPrimitive("write_file")) })
                })
                add(buildJsonObject {
                    put("role", "summarizer")
                    put("task", "summarize C")
                    // no tools → inherits filtered main tools
                })
            })
        }
        val result = tool.execute(args, ctx)
        assertFalse(result.isError)
        assertEquals(3, llm.chatRequests.size)
        val requests = llm.chatRequests
        assertEquals(setOf("read_file"), requests[0].tools.map { it.name }.toSet())
        assertEquals(setOf("read_file", "write_file"), requests[1].tools.map { it.name }.toSet())
        // summarizer inherits everything minus subagent-related → read_file + write_file
        assertEquals(setOf("read_file", "write_file"), requests[2].tools.map { it.name }.toSet())
        assertTrue(result.parts.text.contains("[1] review A"))
        assertTrue(result.parts.text.contains("[2] draft B"))
        assertTrue(result.parts.text.contains("[3] summarize C"))
    }

    @Test
    fun `single agent failure does not cancel siblings and is marked FAILED`() = runTest {
        var callCount = 0
        val llm = object : LlmProvider {
            override val name = "fail-mid"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                callCount++
                if (callCount == 2) throw IllegalStateException("simulated agent 2 failure")
                return chatResponse("ok-$callCount")
            }

            override fun chatStream(request: ChatRequest) =
                flowOf(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(agentJson(role = "a", task = "t1"))
                add(agentJson(role = "b", task = "t2"))
                add(agentJson(role = "c", task = "t3"))
            })
        }
        val result = tool.execute(args, ctx)
        assertTrue(
            result.isError,
            "tool.execute must return error when any agent fails: ${result.parts.text}"
        )
        val parts = result.parts.text.split("\n\n")
        assertEquals(3, parts.size, "expected exactly 3 result parts, got: ${result.parts.text}")
        assertTrue(parts[0].startsWith("[1] t1\nok-1"), "agent 1 should succeed: ${parts[0]}")
        assertTrue(parts[1].startsWith("[2] t2 — FAILED"), "agent 2 should be marked FAILED: ${parts[1]}")
        assertTrue(parts[1].contains("simulated agent 2 failure"), "agent 2 should include error: ${parts[1]}")
        assertTrue(parts[2].startsWith("[3] t3\nok-3"), "agent 3 should succeed: ${parts[2]}")
    }

    @Test
    fun `execute never reads or writes main agent memory`() = runTest {
        val mainMemory = InMemoryMemory()
        val llm = StubLlmProvider(listOf(chatResponse("ok")))
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(llm, memory = mainMemory))
        val args = buildJsonObject {
            put("subagents", buildJsonArray { add(agentJson(role = "p", task = "a separate task")) })
        }
        tool.execute(args, ctx)
        assertTrue(mainMemory.history().isEmpty(), "main agent memory must remain untouched")
    }

    @Test
    fun `agents run in parallel not sequentially`() = runTest {
        val slowLlm = object : LlmProvider {
            override val name = "slow"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                delay(50)
                return chatResponse("ok")
            }

            override fun chatStream(request: ChatRequest) =
                flowOf(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
        val tool = DynamicSubagentTool()
        val ctx = toolCtx(stubAgentContext(slowLlm))
        val args = buildJsonObject {
            put("subagents", buildJsonArray {
                add(agentJson(role = "a", task = "a"))
                add(agentJson(role = "b", task = "b"))
                add(agentJson(role = "c", task = "c"))
            })
        }
        val start = currentTime
        tool.execute(args, ctx)
        val elapsed = currentTime - start
        assertTrue(elapsed < 120, "expected ~50ms (parallel), got ${elapsed}ms (likely sequential)")
    }
}