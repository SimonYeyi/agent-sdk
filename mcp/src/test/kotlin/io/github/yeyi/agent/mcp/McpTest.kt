package io.github.yeyi.agent.mcp

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
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.toolset.ToolsetContext
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpTest {

    private class StubLlm : LlmProvider {
        override val name: String = "stub"
        val recorded: MutableList<ChatRequest> = mutableListOf()
        override suspend fun chat(request: ChatRequest): ChatResponse {
            recorded += request
            return ChatResponse(
                message = ChatMessage.Assistant(content = "ok"),
                finishReason = FinishReason.Stop,
            )
        }
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
            recorded += request
            emit(StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    private fun stubToolContext(): ToolContext = ToolContext(
        toolCallId = "test",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = StubLlm(),
            tools = emptyList(),
            maxRounds = 1,
        ),
    )

    /** 通用假传输：可由测试覆盖 [ListToolsResult] / [CallToolResult] 行为。 */
    private class FakeServerTransport(
        private val listToolsResult: ListToolsResult = ListToolsResult(tools = JsonArray(emptyList())),
        private val callToolResult: CallToolResult = CallToolResult(content = JsonNull),
    ) : McpTransport {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
            return when (request.method) {
                McpMethods.INITIALIZE -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.parseToJsonElement(
                        """{"protocolVersion":"2025-06-18","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}"""
                    ),
                )
                McpMethods.TOOLS_LIST -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.encodeToJsonElement(ListToolsResult.serializer(), listToolsResult),
                )
                McpMethods.TOOLS_CALL -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.encodeToJsonElement(CallToolResult.serializer(), callToolResult),
                )
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    /** 记录 callTool 收到的 params 的传输。 */
    private class CapturingTransport(
        private val callToolResult: CallToolResult = CallToolResult(content = JsonPrimitive("ok")),
    ) : McpTransport {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val capturedParams: MutableList<JsonElement> = mutableListOf()

        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
            return when (request.method) {
                McpMethods.INITIALIZE -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.parseToJsonElement(
                        """{"protocolVersion":"2025-06-18","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}"""
                    ),
                )
                McpMethods.TOOLS_LIST -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.encodeToJsonElement(ListToolsResult.serializer(), ListToolsResult(tools = JsonArray(emptyList()))),
                )
                McpMethods.TOOLS_CALL -> {
                    capturedParams += (request.params ?: JsonNull)
                    JsonRpcResponse(
                        jsonrpc = "2.0",
                        id = request.id,
                        result = json.encodeToJsonElement(CallToolResult.serializer(), callToolResult),
                    )
                }
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    private fun fakeMcp(
        name: String = "test",
        description: String = "d",
        transport: McpTransport = FakeServerTransport(),
        client: McpClient = McpClient(transport),
    ): Mcp = object : Mcp {
        override val name: String = name
        override val description: String = description
        override val client: McpClient = client
    }

    // ---------- Mcp.activate ----------

    @Test
    fun `activate returns the tools list with Toolset-format prefix`() = runTest {
        val tools = JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2")))
        val mcp = fakeMcp(
            name = "calc",
            transport = FakeServerTransport(listToolsResult = ListToolsResult(tools = tools)),
        )

        val result = mcp.activate(null, ToolsetContext())

        assertTrue(result.startsWith("Toolset 'calc' 包含以下子工具 (完整 schema):\n"))
        assertTrue("tool1" in result)
        assertTrue("tool2" in result)
    }

    @Test
    fun `activate with empty tools returns the header and an empty array`() = runTest {
        val mcp = fakeMcp(name = "empty", transport = FakeServerTransport())
        val result = mcp.activate(null, ToolsetContext())
        assertTrue(result.startsWith("Toolset 'empty' 包含以下子工具 (完整 schema):\n"))
        assertTrue(result.endsWith("[]"))
    }

    // ---------- Mcp.add (不支持) ----------

    @Test
    fun `add throws UnsupportedOperationException`() {
        val mcp = fakeMcp(name = "calc")
        val ex = assertFailsWith<UnsupportedOperationException> {
            mcp.add(object : io.github.yeyi.agent.tool.Tool {
                override val name: String = "x"
                override val description: String = "d"
                override val parametersSchema: io.github.yeyi.agent.tool.ToolParameters = io.github.yeyi.agent.tool.ToolParameters.Empty
                override suspend fun execute(
                    arguments: JsonElement,
                    context: io.github.yeyi.agent.tool.ToolContext,
                ): io.github.yeyi.agent.tool.ToolExecutionResult =
                    io.github.yeyi.agent.tool.ToolExecutionResult("ok")
            })
        }
        assertTrue("calc" in (ex.message ?: ""), "exception should mention Mcp name, got: ${ex.message}")
    }

    @Test
    fun `add Iterable throws UnsupportedOperationException`() {
        val mcp = fakeMcp(name = "calc")
        assertFailsWith<UnsupportedOperationException> {
            mcp.add(emptyList<io.github.yeyi.agent.tool.Tool>())
        }
    }

    // ---------- Mcp.dispatch ----------

    @Test
    fun `dispatch wraps sub_tool_name and sub_tool_arguments into MCP tools-call envelope`() = runTest {
        val transport = CapturingTransport(callToolResult = CallToolResult(content = JsonPrimitive("42")))
        val mcp = fakeMcp(name = "calc", transport = transport)
        val args = buildJsonObject { put("a", JsonPrimitive(1)); put("b", JsonPrimitive(2)) }

        val out = mcp.dispatch("add", args, stubToolContext())

        assertFalse(out.isError)
        // JsonPrimitive("42").toString() returns the JSON-encoded form "\"42\""
        assertEquals("\"42\"", out.content)
        val params = transport.capturedParams.single().jsonObject
        assertEquals("add", params["name"]!!.jsonPrimitive.content)
        assertEquals(args, params["arguments"])
    }

    @Test
    fun `dispatch passes JsonNull arguments when arguments are null`() = runTest {
        val transport = CapturingTransport(callToolResult = CallToolResult(content = JsonPrimitive("ok")))
        val mcp = fakeMcp(name = "calc", transport = transport)

        mcp.dispatch("ping", JsonNull, stubToolContext())

        val params = transport.capturedParams.single().jsonObject
        assertEquals("ping", params["name"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, params["arguments"])
    }

    @Test
    fun `dispatch propagates McpException when client returns isError=true`() = runTest {
        val transport = FakeServerTransport(
            callToolResult = CallToolResult(content = JsonArray(listOf(JsonPrimitive("err msg"))), isError = true),
        )
        val mcp = fakeMcp(name = "calc", transport = transport)
        assertFailsWith<McpException> {
            mcp.dispatch("add", JsonObject(emptyMap()), stubToolContext())
        }
    }

    // ---------- McpRegistry ----------

    @Test
    fun `register injects clientInfo into the underlying McpClient`() {
        val info = ClientInfo("test-app", "9.9.9")
        val client = McpClient(FakeServerTransport())
        val mcp = fakeMcp(client = client)
        val registry = McpRegistry(ToolsetRegistry(), info)
        registry.register(mcp)
        assertSame(info, client.clientInfo)
    }

    @Test
    fun `register iterable registers multiple Mcps`() {
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("", ""))
        registry.register(listOf(fakeMcp(name = "a"), fakeMcp(name = "b")))
        assertEquals(setOf("a", "b"), registry.toolsetRegistry.all().map { it.name }.toSet())
    }

    @Test
    fun `registering duplicate Mcp name throws IllegalArgumentException`() {
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("", ""))
        registry.register(fakeMcp(name = "dup"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(fakeMcp(name = "dup"))
        }
    }

    @Test
    fun `unregisterAll closes every McpClient and empties the internal registry`() {
        val closed = mutableListOf<String>()
        fun makeMcp(name: String): Mcp = object : Mcp {
            override val name: String = name
            override val description: String = "d"
            override val client: McpClient = McpClient(object : McpTransport {
                override suspend fun send(request: JsonRpcRequest<JsonElement>) = error("not used")
                override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
                override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
                override suspend fun close() { closed += name }
            })
        }
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("", ""))
        registry.register(makeMcp("a"))
        registry.register(makeMcp("b"))

        kotlinx.coroutines.runBlocking { registry.unregisterAll().join() }

        assertEquals(setOf("a", "b"), closed.toSet())
        assertEquals(0, registry.toolsetRegistry.all().size)
    }

    // ---------- mcps DSL ----------

    @Test
    fun `mcps DSL installs load_toolset and sub_tool_delegate on Agent`() = runTest {
        val llm = StubLlm()
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        AgentBuilder().apply {
            llmProvider(llm)
            mcps(registry)
        }.build().run("hi").toList()

        val names = llm.recorded.single().tools.map { it.name }.toSet()
        assertTrue("load_toolset" in names, "expected load_toolset in $names")
        assertTrue("sub_tool_delegate" in names, "expected sub_tool_delegate in $names")
    }

    @Test
    fun `mcps DSL load_toolset description lists registered Mcps by name and description`() = runTest {
        val llm = StubLlm()
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
            register(fakeMcp(name = "news", description = "news-desc"))
        }
        AgentBuilder().apply {
            llmProvider(llm)
            mcps(registry)
        }.build().run("hi").toList()

        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description)
        assertTrue("calc-desc" in loadToolset.description)
        assertTrue("news" in loadToolset.description)
        assertTrue("news-desc" in loadToolset.description)
    }

    @Test
    fun `mcps and toolsets together throw IllegalStateException with mcps-specific guidance`() = runTest {
        val mcpReg = McpRegistry(ToolsetRegistry(), ClientInfo("", ""))
        val tsReg = ToolsetRegistry()

        val ex = assertFailsWith<IllegalStateException> {
            AgentBuilder().apply {
                llmProvider(StubLlm())
                toolsets(tsReg)
                mcps(mcpReg)
            }.build()
        }
        assertTrue(
            ex !is io.github.yeyi.agent.toolset.ToolsetsInstallException,
            "mcps() should re-wrap the exception as a plain IllegalStateException, got: ${ex.javaClass.name}",
        )
        val msg = ex.message ?: ""
        assertTrue("mcps" in msg, "message should mention mcps: $msg")
        assertTrue(
            "toolsets" in msg && ("load_toolset" in msg || "sub_tool_delegate" in msg),
            "message should mention toolsets and the conflicting tool name: $msg",
        )
        assertTrue(
            ex.cause is io.github.yeyi.agent.toolset.ToolsetsInstallException,
            "cause should be ToolsetsInstallException, got: ${ex.cause?.javaClass?.name}",
        )
    }

    @Test
    fun `mcps then toolsets throws ToolsetsInstallException pointing user to grep toolsets (reverse direction)`() = runTest {
        val mcpReg = McpRegistry(ToolsetRegistry(), ClientInfo("", ""))
        val tsReg = ToolsetRegistry()

        val ex = assertFailsWith<io.github.yeyi.agent.toolset.ToolsetsInstallException> {
            AgentBuilder().apply {
                llmProvider(StubLlm())
                mcps(mcpReg)
                toolsets(tsReg)
            }.build()
        }
        val msg = ex.message ?: ""
        assertTrue(
            "load_toolset" in msg && "sub_tool_delegate" in msg,
            "message should name both conflicting tools: $msg",
        )
        assertTrue(
            "higher-level DSL" in msg && "kdoc will mention" in msg,
            "message should direct user to check kdoc of higher-level DSLs (which finds the mcps() call) to find the duplicate: $msg",
        )
        assertTrue(
            ex.cause is io.github.yeyi.agent.tool.ToolDuplicateException,
            "cause should be ToolDuplicateException, got: ${ex.cause?.javaClass?.name}",
        )
    }
}
