package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
    override val name: String = "local-demo"
    override val description: String = "Local demo server"

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
        buildJsonObject {
            put("name", "add")
            put("description", "Add two numbers")
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("a", buildJsonObject { put("type", "number") })
                    put("b", buildJsonObject { put("type", "number") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
            })
        }
    )

    override suspend fun initialize(): InitializeResult {
        initialized = true
        return InitializeResult(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            serverInfo = ServerInfo(name, "1.0.0"),
            capabilities = ServerCapabilities(tools = ToolsObject(listChanged = false)),
        )
    }

    override suspend fun listTools(cursor: String?): ListToolsResult {
        return ListToolsResult(
            tools = kotlinx.serialization.json.JsonArray(tools),
            nextCursor = null,
        )
    }

    override suspend fun callTool(params: JsonElement): JsonElement {
        val obj = params.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content
        val args = obj["arguments"]?.jsonObject

        return when (name) {
            "add" -> {
                val a = args?.get("a")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val b = args?.get("b")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                buildJsonObject {
                    put(
                        "content", JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text"); put("text", (a + b).toString())
                                }
                            )))
                }
            }
            else -> buildJsonObject {
                put("isError", true)
                put(
                    "content", JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "text"); put("text", "Unknown tool: $name")
                            }
                        )))
            }
        }
    }

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    fun isInitialized() = initialized
    fun lastReceivedNotification() = receivedNotification
}

class LocalTransportTest {
    private fun createRegistry(): Pair<LocalMcpServerForTest, McpServerRegistry> {
        val server = LocalMcpServerForTest()
        val registry = McpServerRegistry(ClientInfo("test", "1.0")).apply {
            register(GenericMcpServer("local-demo", "Local demo", LocalTransport(server)))
        }
        return server to registry
    }

    @Test
    fun `listAllTools returns tools`() = runTest {
        val (server, registry) = createRegistry()

        val result = registry.listAllTools("local-demo")

        assertEquals(1, result.tools.size)
        assertEquals("add", result.tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callTool add returns correct result`() = runTest {
        val (_, registry) = createRegistry()

        val result = registry.callTool(
            "local-demo",
            buildJsonObject {
                put("name", "add")
                put("arguments", buildJsonObject {
                    put("a", 3)
                    put("b", 7)
                })
            }
        )

        val text = result.jsonObject["content"]
            ?.jsonArray?.get(0)?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
        assertEquals("10.0", text)
    }

    @Test
    fun `callTool unknown tool returns error`() = runTest {
        val (_, registry) = createRegistry()

        val result = registry.callTool(
            "local-demo",
            buildJsonObject {
                put("name", "unknown_tool")
                put("arguments", buildJsonObject { })
            }
        )

        assertTrue(result.jsonObject["isError"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun `server initialize is called`() = runTest {
        val (server, registry) = createRegistry()

        registry.listAllTools("local-demo")

        assertTrue(server.isInitialized())
    }

    @Test
    fun `server receives notifications initialized`() = runTest {
        val (server, registry) = createRegistry()

        // Force initialization which sends notifications/initialized
        registry.listAllTools("local-demo")

        val notification = server.lastReceivedNotification()
        assertNotNull(notification)
        assertEquals(McpMethods.NOTIFICATIONS_INITIALIZED, notification.method)
    }

    @Test
    fun `server not found throws`() = runTest {
        val (_, registry) = createRegistry()

        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            registry.listAllTools("non-existent")
        }
        assertTrue(exception.message?.contains("non-existent") == true)
    }
}
