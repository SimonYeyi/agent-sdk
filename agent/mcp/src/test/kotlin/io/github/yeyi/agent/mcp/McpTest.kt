package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toolset.Toolset
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            recorded += request
            emit(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    private fun stubToolExecutionContext(): ToolExecutionContext = ToolExecutionContext(
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
        private val listToolsResult: ListToolsResult = ListToolsResult(tools = emptyList()),
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

        override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    /** 记录 callTool 收到的 params 的传输。 */
    private class CapturingTransport(
        private val listToolsResult: ListToolsResult = ListToolsResult(tools = emptyList()),
        private val callToolResult: CallToolResult = CallToolResult(content = JsonPrimitive("ok")),
    ) : McpTransport {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val capturedParams: MutableList<CallToolParams> = mutableListOf()

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
                McpMethods.TOOLS_CALL -> {
                    val callToolParams = request.params
                        ?.let { json.decodeFromJsonElement(CallToolParams.serializer(), it) }
                        ?: error("Missing params for tools/call")
                    capturedParams += callToolParams
                    JsonRpcResponse(
                        jsonrpc = "2.0",
                        id = request.id,
                        result = json.encodeToJsonElement(CallToolResult.serializer(), callToolResult),
                    )
                }
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    private fun fakeMcp(
        name: String = "test",
        description: String = "d",
        transport: McpTransport = FakeServerTransport(),
        client: McpClient = McpClient(transport),
    ): Mcp = object : Mcp() {
        override val name: String = name
        override val description: String = description
        override val client: McpClient = client
    }

    // ---------- Mcp.activate ----------

    @Test
    fun `activate includes each MCP server tool name in the output`() = runTest {
        val tools = listOf(
            ToolDef("tool1", "tool1 desc", buildJsonObject { put("type", JsonPrimitive("object")) }),
            ToolDef("tool2", "tool2 desc", buildJsonObject { put("type", JsonPrimitive("object")) }),
        )
        val mcp = fakeMcp(
            name = "calc",
            transport = FakeServerTransport(listToolsResult = ListToolsResult(tools = tools)),
        )

        val result = mcp.activate(null, ToolsetContext())

        assertTrue("tool1" in result, "expected 'tool1' in activate output, got: $result")
        assertTrue("tool2" in result, "expected 'tool2' in activate output, got: $result")
    }

    @Test
    fun `activate succeeds when the MCP server returns no tools`() = runTest {
        val mcp = fakeMcp(name = "empty", transport = FakeServerTransport())
        val result = mcp.activate(null, ToolsetContext())
        // Empty-output formatting belongs to Toolset's own tests; here we only verify
        // MCP doesn't crash and the toolset name is mentioned so the LLM can scope later calls.
        assertTrue(result.isNotEmpty(), "activate should still return a header for an empty toolset, got empty")
        assertTrue("empty" in result, "activate output should mention the MCP name, got: $result")
    }

    // ---------- Mcp.add 抛异常 ----------

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
                    context: io.github.yeyi.agent.tool.ToolExecutionContext,
                ): io.github.yeyi.agent.tool.ToolExecutionResult =
                    io.github.yeyi.agent.tool.ToolExecutionResult.success("ok")
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

    // ---------- Mcp.get + execute ----------

    @Test
    fun `execute wraps tool_name and tool_arguments into MCP tools-call envelope`() = runTest {
        val transport = CapturingTransport(
            listToolsResult = ListToolsResult(listOf(ToolDef("add", "add", buildJsonObject { put("type", JsonPrimitive("object")) }))),
            callToolResult = CallToolResult(content = JsonPrimitive("42")),
        )
        val mcp = fakeMcp(name = "calc", transport = transport)
        // 模拟 LLM 流程：先访问 all() 触发 delegate 懒初始化
        mcp.all()
        val args = buildJsonObject { put("a", JsonPrimitive(1)); put("b", JsonPrimitive(2)) }

        val out = mcp.get("add").execute(args, stubToolExecutionContext())

        assertFalse(out.isError)
        // JsonPrimitive("42").toString() returns the JSON-encoded form "\"42\""
        assertEquals("\"42\"", out.parts.text)
        val params = transport.capturedParams.single()
        assertEquals("add", params.name)
        // Mcp 自身不做参数解码，arguments 原样透传给 MCP server
        assertEquals(args, params.arguments)
    }

    @Test
    fun `execute passes arguments through unchanged`() = runTest {
        val transport = CapturingTransport(
            listToolsResult = ListToolsResult(listOf(ToolDef("ping", "ping", buildJsonObject { put("type", JsonPrimitive("object")) }))),
            callToolResult = CallToolResult(content = JsonPrimitive("ok")),
        )
        val mcp = fakeMcp(name = "calc", transport = transport)
        mcp.all()
        val args = buildJsonObject { put("any", JsonPrimitive("value")) }

        mcp.get("ping").execute(args, stubToolExecutionContext())

        val params = transport.capturedParams.single()
        assertEquals("ping", params.name)
        assertEquals(args, params.arguments)
    }

    @Test
    fun `execute propagates McpException when client returns isError=true`() = runTest {
        val transport = FakeServerTransport(
            listToolsResult = ListToolsResult(listOf(ToolDef("add", "add", buildJsonObject { put("type", JsonPrimitive("object")) }))),
            callToolResult = CallToolResult(content = JsonArray(listOf(JsonPrimitive("err msg"))), isError = true),
        )
        val mcp = fakeMcp(name = "calc", transport = transport)
        mcp.all()
        assertFailsWith<McpException> {
            mcp.get("add").execute(buildJsonObject { put("a", JsonPrimitive(1)) }, stubToolExecutionContext())
        }
    }

    // ---------- McpRegistry ----------

    @Test
    fun `register injects clientInfo into the underlying McpClient`() {
        val info = ClientInfo("test-app", "9.9.9")
        val client = McpClient(FakeServerTransport())
        val mcp = fakeMcp(client = client)
        val registry = McpRegistry(info)
        registry.register(mcp)
        assertSame(info, client.clientInfo)
    }

    @Test
    fun `register iterable registers multiple Mcps`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(listOf(fakeMcp(name = "a"), fakeMcp(name = "b")))
        assertEquals(setOf("a", "b"), registry.all().map { it.name }.toSet())
    }

    @Test
    fun `all returns registered Mcps in registration order`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(fakeMcp(name = "a"))
        registry.register(fakeMcp(name = "b"))
        assertEquals(listOf("a", "b"), registry.all().map { it.name })
    }

    @Test
    fun `registering duplicate Mcp name throws IllegalArgumentException`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(fakeMcp(name = "dup"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(fakeMcp(name = "dup"))
        }
    }

    @Test
    fun `unregisterAll closes every McpClient and empties the internal registry`() {
        val closed = mutableListOf<String>()
        fun makeMcp(name: String): Mcp = object : Mcp() {
            override val name: String = name
            override val description: String = "d"
            override val client: McpClient = McpClient(object : McpTransport {
                override suspend fun send(request: JsonRpcRequest<JsonElement>) = error("not used")
                override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) = Unit
                override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
                override suspend fun close() { closed += name }
            })
        }
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(makeMcp("a"))
        registry.register(makeMcp("b"))

        kotlinx.coroutines.runBlocking { registry.unregisterAll().join() }

        assertEquals(setOf("a", "b"), closed.toSet())
        assertEquals(0, registry.all().size)
    }

    // ---------- mergeInto / shared storage ----------

    @Test
    fun `mergeInto moves storage to the target registry keeping entries`() {
        val registry = McpRegistry(ClientInfo("", ""))
        val mcp = fakeMcp(name = "calc", description = "calc-desc")
        registry.register(mcp)

        val shared = io.github.yeyi.agent.toolset.ToolsetRegistry()
        registry.mergeInto(shared)

        assertSame(mcp, shared.get("calc"))
        // mergeInto 后 register 直接作用于共享本体
        val late = fakeMcp(name = "news")
        registry.register(late)
        assertSame(late, shared.get("news"))
    }

    @Test
    fun `mergeInto same registry twice is a no-op`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(fakeMcp(name = "calc"))

        val shared = io.github.yeyi.agent.toolset.ToolsetRegistry()
        registry.mergeInto(shared)
        // 第二次自并自若无恒等守卫会撞重名 require
        registry.mergeInto(shared)

        assertEquals(1, shared.all().size)
    }

    @Test
    fun `unregisterAll removes Mcp entries from shared registry but keeps plain toolsets`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(fakeMcp(name = "calc"))

        val shared = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(io.github.yeyi.agent.toolset.Toolset("weather", "weather-desc"))
        }
        registry.mergeInto(shared)

        kotlinx.coroutines.runBlocking { registry.unregisterAll().join() }

        assertNull(shared.getOrNull("calc"), "Mcp entry must be removed from the shared registry")
        assertNotNull(shared.getOrNull("weather"), "plain Toolset must survive unregisterAll")
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `register after mcps DSL is visible via the shared registry`() = runTest {
        val llm = StubLlm()
        val registry = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        val builder = AgentBuilder().apply {
            llmProvider(llm)
            mcps(registry)
        }
        // DSL 之后 register 动态传播到共享本体(共享语义)
        val late = fakeMcp(name = "news", description = "news-desc")
        registry.register(late)

        builder.build().run(AgentQuery.text("hi")).toList()

        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description)
        assertTrue("news" in loadToolset.description, "post-DSL register should be visible: ${loadToolset.description}")
    }

    // ---------- mcps DSL ----------

    @Test
    fun `mcps DSL installs load_toolset and member_tool_delegate on Agent`() = runTest {
        val llm = StubLlm()
        val registry = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        AgentBuilder().apply {
            llmProvider(llm)
            mcps(registry)
        }.build().run(AgentQuery.text("hi")).toList()

        val names = llm.recorded.single().tools.map { it.name }.toSet()
        assertTrue("load_toolset" in names, "expected load_toolset in $names")
        assertTrue("member_tool_delegate" in names, "expected member_tool_delegate in $names")
    }

    @Test
    fun `mcps DSL load_toolset description lists registered Mcps by name and description`() = runTest {
        val llm = StubLlm()
        val registry = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
            register(fakeMcp(name = "news", description = "news-desc"))
        }
        AgentBuilder().apply {
            llmProvider(llm)
            mcps(registry)
        }.build().run(AgentQuery.text("hi")).toList()

        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description)
        assertTrue("calc-desc" in loadToolset.description)
        assertTrue("news" in loadToolset.description)
        assertTrue("news-desc" in loadToolset.description)
    }

    @Test
    fun `mcps and toolsets together merge into one shared toolset plugin`() = runTest {
        val llm = StubLlm()
        val mcpReg = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        val tsReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather-desc").apply { add(stubTool("get_weather")) })
        }

        AgentBuilder().apply {
            llmProvider(llm)
            toolsets(tsReg)
            mcps(mcpReg)
        }.build().run(AgentQuery.text("hi")).toList()

        val names = llm.recorded.single().tools.map { it.name }
        assertEquals(1, names.count { it == "load_toolset" }, "expected exactly one load_toolset, got: $names")
        assertEquals(1, names.count { it == "member_tool_delegate" }, "expected exactly one member_tool_delegate, got: $names")
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description, "merged Mcp 'calc' should be visible: ${loadToolset.description}")
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
    }

    @Test
    fun `mcps then toolsets merges in reverse order`() = runTest {
        val llm = StubLlm()
        val mcpReg = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        val tsReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather-desc").apply { add(stubTool("get_weather")) })
        }

        AgentBuilder().apply {
            llmProvider(llm)
            mcps(mcpReg)
            toolsets(tsReg)
        }.build().run(AgentQuery.text("hi")).toList()

        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description, "merged Mcp 'calc' should be visible: ${loadToolset.description}")
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
    }

    private fun stubTool(name: String): Tool = object : Tool {
        override val name: String = name
        override val description: String = "stub"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(
            arguments: kotlinx.serialization.json.JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult.success("ok")
    }
}
