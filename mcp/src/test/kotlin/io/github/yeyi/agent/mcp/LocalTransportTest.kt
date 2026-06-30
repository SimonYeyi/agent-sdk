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
            serverInfo = ServerInfo("local-demo", "1.0.0"),
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
    private fun createRegistry(): Pair<LocalMcpServerForTest, McpRegistry> {
        val server = LocalMcpServerForTest()
        val registry = McpRegistry(ClientInfo("test", "1.0")).apply {
            register(object : Mcp {
                override val name: String = "local-demo"
                override val description: String = "Local demo server"
                override val client: McpClient = McpClient(LocalTransport(server))
            })
        }
        return server to registry
    }

    @Test
    fun `listAllTools returns tools`() = runTest {
        val (_, registry) = createRegistry()
        val mcp = registry.all().single()

        val result = mcp.client.toolsList().tools.toString()

        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonArray
        assertEquals(1, json.size)
        assertEquals("add", json[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `callTool add returns correct result`() = runTest {
        val (_, registry) = createRegistry()

        val result = registry.toolsCall(
            "local-demo",
            buildJsonObject {
                put("name", "add")
                put("arguments", buildJsonObject {
                    put("a", 3)
                    put("b", 7)
                })
            }
        )

        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        val text = json["content"]
            ?.jsonArray?.get(0)?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
        assertEquals("10.0", text)
    }

    @Test
    fun `callTool unknown tool returns error`() = runTest {
        val (_, registry) = createRegistry()

        val result = registry.toolsCall(
            "local-demo",
            buildJsonObject {
                put("name", "unknown_tool")
                put("arguments", buildJsonObject { })
            }
        )

        val json = kotlinx.serialization.json.Json.parseToJsonElement(result).jsonObject
        assertTrue(json["isError"]?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun `server initialize is called`() = runTest {
        val (server, registry) = createRegistry()
        val mcp = registry.all().single()

        mcp.client.toolsList()

        assertTrue(server.isInitialized())
    }

    @Test
    fun `server receives notifications initialized`() = runTest {
        val (server, registry) = createRegistry()
        val mcp = registry.all().single()

        mcp.client.toolsList()

        val notification = server.lastReceivedNotification()
        assertNotNull(notification)
        assertEquals(McpMethods.NOTIFICATIONS_INITIALIZED, notification.method)
    }

    @Test
    fun `server not found throws`() = runTest {
        val (_, registry) = createRegistry()

        val exception = kotlin.test.assertFailsWith<NoSuchElementException> {
            registry.toolsCall(
                "non-existent",
                buildJsonObject {
                    put("name", "x")
                    put("arguments", buildJsonObject { })
                }
            )
        }
        assertTrue(exception.message?.contains("non-existent") == true)
    }
}
