package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class LocalMcpServerForTest : McpServer {
    private val _notifications = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var initialized = false
    private var receivedNotification: JsonRpcRequest<JsonElement>? = null

    override val transport: McpTransport = LocalTransport.forServer(_notifications) { req ->
        receivedNotification = req
    }

    private val tools = listOf(
        ToolDef(
            name = "add",
            description = "Add two numbers",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            },
        )
    )

    override suspend fun initialize(): InitializeResult {
        initialized = true
        return InitializeResult(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            serverInfo = ServerInfo("local-demo", "1.0.0"),
            capabilities = ServerCapabilities(tools = ToolsObject(listChanged = false)),
        )
    }

    override suspend fun listTools(cursor: String?): ListToolsResult {
        return ListToolsResult(
            tools = tools,
            nextCursor = null,
        )
    }

    override suspend fun callTool(params: CallToolParams): JsonElement {
        val name = params.name
        val args = params.arguments

        return when (name) {
            "add" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                JsonArray(
                    listOf(buildJsonObject {
                        put("type", "text")
                        put("text", (a + b).toString())
                    })
                )
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    fun isInitialized() = initialized
    fun lastReceivedNotification() = receivedNotification
}

class LocalTransportTest {
    private object UnusedLlm : LlmProvider {
        override val name: String = "unused"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            error("LlmProvider.chat must not be called in LocalTransportTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("unused")))
    }

    private fun stubToolContext(): ToolContext = ToolContext(
        toolCallId = "test",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = UnusedLlm,
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    private fun createRegistry(): Triple<LocalMcpServerForTest, Mcp, McpRegistry> {
        val server = LocalMcpServerForTest()
        val mcp = object : Mcp() {
            override val name: String = "local-demo"
            override val description: String = "Local demo server"
            override val client: McpClient = McpClient(LocalTransport(server))
        }
        val registry = McpRegistry(ToolsetRegistry(), ClientInfo("test", "1.0")).apply { register(mcp) }
        return Triple(server, mcp, registry)
    }

    @Test
    fun `listAllTools returns tools`() = runTest {
        val (_, mcp, _) = createRegistry()

        val tools = mcp.client.toolsList().tools

        assertEquals(1, tools.size)
        assertEquals("add", tools[0].name)
    }

    @Test
    fun `callTool add returns correct result`() = runTest {
        val (_, mcp, _) = createRegistry()
        // 模拟 LLM 流程：先访问 definitions() 触发 CompressTool.parametersSchema 懒加载
        mcp.definitions()

        val out = mcp.dispatch(
            "add",
            buildJsonObject {
                put("execution", JsonPrimitive("add(a=3, b=7)"))
            },
            stubToolContext(),
        )

        val text = kotlinx.serialization.json.Json.parseToJsonElement(out.content)
            .jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        assertEquals("10.0", text)
    }

    @Test
    fun `callTool unknown tool returns isError in content`() = runTest {
        val (_, mcp, _) = createRegistry()
        mcp.definitions()

        val out = mcp.dispatch("unknown_tool", buildJsonObject { }, stubToolContext())

        assertTrue(out.isError)
        assertTrue("unknown_tool" in out.content)
    }

    @Test
    fun `server initialize is called`() = runTest {
        val (server, mcp, _) = createRegistry()

        mcp.client.toolsList()

        assertTrue(server.isInitialized())
    }

    @Test
    fun `server receives notifications initialized`() = runTest {
        val (server, mcp, _) = createRegistry()

        mcp.client.toolsList()

        val notification = server.lastReceivedNotification()
        assertNotNull(notification)
        assertEquals(McpMethods.NOTIFICATIONS_INITIALIZED, notification.method)
    }

    @Test
    fun `server not found throws NoSuchElementException`() = runTest {
        val (_, _, registry) = createRegistry()

        val ex = kotlin.test.assertFailsWith<NoSuchElementException> {
            registry.toolsetRegistry.get("non-existent")
        }
        assertTrue(ex.message?.contains("non-existent") == true)
    }
}
