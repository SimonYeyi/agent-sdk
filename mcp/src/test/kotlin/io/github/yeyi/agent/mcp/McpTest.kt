package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpTest {

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in McpTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("not used")))
    }

    private fun stubToolContext(): ToolContext = ToolContext(
        toolCallId = "test",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = StubLlm,
            tools = emptyList(),
            maxRounds = 1,
        ),
    )

    /** 通用假传输：默认 [ListToolsResult] 可由测试覆盖。 */
    private class FakeServerTransport(
        private val listToolsResult: ListToolsResult = ListToolsResult(tools = JsonArray(emptyList())),
    ) : McpTransport {
        private val json = kotlinx.serialization.json.Json {
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
                    result = json.encodeToJsonElement(
                        CallToolResult.serializer(),
                        CallToolResult(content = JsonNull),
                    ),
                )
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    private fun createFakeMcp(
        name: String = "test",
        description: String = "test",
        listToolsResult: ListToolsResult = ListToolsResult(tools = JsonArray(emptyList())),
        client: McpClient = McpClient(FakeServerTransport(listToolsResult)),
    ): Mcp = object : Mcp {
        override val name: String = name
        override val description: String = description
        override val client: McpClient = client
    }

    // ---------- Mcp 自身契约 ----------

    @Test
    fun `Mcp has CAPABILITY_NAME constant mcp`() {
        assertEquals("mcp", Mcp.CAPABILITY_NAME)
    }

    @Test
    fun `activate returns the tools list with discovery prefix`() = runTest {
        val tools = JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2")))
        val mcp = createFakeMcp(
            listToolsResult = ListToolsResult(tools = tools),
        )

        val result = mcp.activate(null, McpContext())

        assertTrue(result.startsWith("发现以下可用 MCP 工具："))
        assertTrue(result.contains("tool1"))
        assertTrue(result.contains("tool2"))
    }

    // ---------- McpRegistry ----------

    @Test
    fun `registry capabilityName is mcp`() {
        val r = McpRegistry(ClientInfo("", ""))
        assertEquals(Mcp.CAPABILITY_NAME, r.capabilityName)
        assertEquals("mcp", r.capabilityName)
    }

    @Test
    fun `register adds Mcp to registry`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(createFakeMcp(name = "alpha"))
        val names = registry.all().map { it.name }.toSet()
        assertTrue("alpha" in names)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `register iterable registers each Mcp`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(listOf(
            createFakeMcp(name = "a"),
            createFakeMcp(name = "b"),
        ))
        assertEquals(2, registry.all().size)
        assertEquals(setOf("a", "b"), registry.all().map { it.name }.toSet())
    }

    @Test
    fun `registering duplicate Mcp name throws`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(createFakeMcp(name = "dup"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(createFakeMcp(name = "dup"))
        }
    }

    @Test
    fun `register injects clientInfo into the underlying McpClient`() {
        val info = ClientInfo("test-app", "9.9.9")
        val client = McpClient(FakeServerTransport())
        val mcp = createFakeMcp(client = client)
        val registry = McpRegistry(info)
        registry.register(mcp)
        assertSame(info, client.clientInfo)
    }

    @Test
    fun `all returns all registered Mcps`() {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(createFakeMcp(name = "alpha"))
        registry.register(createFakeMcp(name = "beta"))
        val all = registry.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "alpha" })
        assertTrue(all.any { it.name == "beta" })
    }

    @Test
    fun `unregisterAll closes every McpClient and empties the registry`() = runTest {
        val closed = mutableListOf<String>()
        fun makeMcp(name: String): Mcp = object : Mcp {
            override val name: String = name
            override val description: String = "d"
            override val client: McpClient = McpClient(object : McpTransport {
                override suspend fun send(request: JsonRpcRequest<JsonElement>) =
                    error("not used")
                override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
                override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
                override suspend fun close() { closed += name }
            })
        }
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(makeMcp("a"))
        registry.register(makeMcp("b"))

        registry.unregisterAll()

        assertEquals(setOf("a", "b"), closed.toSet())
        assertEquals(0, registry.all().size)
    }

    @Test
    fun `toolsCall on registry delegates to the underlying McpClient`() = runTest {
        val tools = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val registry = McpRegistry(ClientInfo("", "")).apply {
            register(createFakeMcp(name = "x", listToolsResult = ListToolsResult(tools = tools)))
        }
        val result = registry.toolsCall(
            "x",
            kotlinx.serialization.json.JsonObject(mapOf("name" to JsonPrimitive("any"))),
        )
        // 假传输对 tools/call 返回 content=JsonNull, 序列化时为 `null`,
        // 反序列化后 Kotlin 端是 `null`, 触发 `?: JsonObject(emptyMap())`
        // 回退, toString() 后是 "{}"
        assertEquals("{}", result)
    }

    @Test
    fun `toolsCall on registry throws NoSuchElementException for unknown Mcp`() = runTest {
        val registry = McpRegistry(ClientInfo("", ""))
        registry.register(createFakeMcp(name = "x"))

        assertFailsWith<NoSuchElementException> {
            registry.toolsCall(
                "missing",
                kotlinx.serialization.json.JsonObject(mapOf("name" to JsonPrimitive("any"))),
            )
        }
    }

    // ---------- McpContext / McpContextFactory ----------

    @Test
    fun `McpContextFactory returns a fresh McpContext per call`() {
        val factory = McpContextFactory()
        val a = factory.create(stubToolContext())
        val b = factory.create(stubToolContext())
        assertNotSame(a, b)
    }
}
