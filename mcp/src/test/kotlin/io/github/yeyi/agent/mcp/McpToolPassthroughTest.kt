package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class McpToolPassthroughTest {
    /**
     * Minimal McpServer stand-in that returns preconfigured results for
     * [callTool] and [listAllTools], used to drive CallMcpTool/LoadMcpTool
     * without a real transport.
     */
    private class FakeServer(
        override val name: String,
        override val description: String,
        private val toolCallResult: JsonElement = JsonNull,
        private val toolCallError: Boolean = false,
        private val listAllToolsResult: JsonElement = buildJsonObject {
            put("tools", JsonArray(emptyList()))
        },
    ) : McpServer {
        private val json = Json { ignoreUnknownKeys = true }

        override val transport: McpTransport = NoopTransport
        override suspend fun initialize(): InitializeResult = InitializeResult(
            protocolVersion = "",
            serverInfo = ServerInfo(name = "", version = ""),
        )
        override suspend fun listTools(cursor: String?): ListToolsResult =
            json.decodeFromJsonElement(ListToolsResult.serializer(), listAllToolsResult)
        suspend fun listAllTools(): JsonElement = listAllToolsResult
        override suspend fun ping(): Boolean = true
        override suspend fun callTool(params: JsonElement): JsonElement {
            if (toolCallError) {
                throw MCPServerException(toolCallResult.toString())
            }
            return toolCallResult
        }
        override suspend fun close() = Unit
    }

    private object NoopTransport : McpTransport {
        override suspend fun <T> send(request: JsonRpcRequest<T>): JsonRpcResponse<JsonElement> =
            error("not used")
        override suspend fun <T> sendNotification(request: JsonRpcRequest<T>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `CallMcpTool throws MCPServerException when isError true`() = runTest {
        val contentArray = JsonArray(emptyList())
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "fake",
                description = "fake",
                toolCallResult = contentArray,
                toolCallError = true,
            )
        )
        val tool = CallMcpTool(registry)

        val exception = assertFailsWith<MCPServerException> {
            tool.execute(
                arguments = buildJsonObject {
                    put("server_name", "fake")
                    putJsonObject("params") {
                        put("name", "noop")
                        put("arguments", JsonObject(emptyMap()))
                    }
                },
                context = ToolContext(toolCallId = "test"),
            )
        }

        assertEquals(contentArray.toString(), exception.message?.substringAfter("MCP Server Exception: "))
    }

    @Test
    fun `CallMcpTool returns content when isError false`() = runTest {
        val contentArray = JsonArray(listOf(JsonPrimitive("ok")))
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "fake",
                description = "fake",
                toolCallResult = contentArray,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("server_name", "fake")
                putJsonObject("params") {
                    put("name", "noop")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals(contentArray.toString(), output.content)
    }

    @Test
    fun `CallMcpTool returns empty content when content is absent`() = runTest {
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "fake",
                description = "fake",
                toolCallResult = JsonNull,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("server_name", "fake")
                putJsonObject("params") {
                    put("name", "noop")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals("null", output.content)
    }

    @Test
    fun `LoadMcpTool passes through listAllTools result as content`() = runTest {
        val expected = JsonArray(listOf(JsonPrimitive("x")))
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "fake",
                description = "fake",
                listAllToolsResult = buildJsonObject {
                    put("tools", expected)
                },
            )
        )
        val tool = LoadMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject { put("server_name", "fake") },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals(expected.toString(), output.content)
    }

    @Test
    fun `LoadMcpTool description is built lazily and reflects registered servers`() = runTest {
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "lazy-server",
                description = "lazy test",
                toolCallResult = JsonNull,
            )
        )
        val tool = LoadMcpTool(registry)
        val desc = tool.description
        assertTrue(
            desc.contains("lazy-server"),
            "expected description to contain registered server name; got: $desc"
        )
    }

    @Test
    fun `listAllTools aggregates multiple pages`() = runTest {
        val page1 = buildJsonObject {
            put("tools", JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2"))))
            put("nextCursor", "page2")
        }
        val page2 = buildJsonObject {
            put("tools", JsonArray(listOf(JsonPrimitive("tool3"))))
            put("nextCursor", null)
        }
        var callCount = 0
        val fakeServer = object : McpServer {
            override val name: String = "test"
            override val description: String = "test"
            override val transport: McpTransport = NoopTransport
            override suspend fun initialize(): InitializeResult = InitializeResult(
                protocolVersion = "",
                serverInfo = ServerInfo(name = "", version = ""),
            )
            override suspend fun listTools(cursor: String?): ListToolsResult {
                callCount++
                return if (cursor == null) {
                    Json.decodeFromJsonElement(ListToolsResult.serializer(), page1)
                } else {
                    Json.decodeFromJsonElement(ListToolsResult.serializer(), page2)
                }
            }
            override suspend fun ping(): Boolean = true
            override suspend fun callTool(params: JsonElement): JsonElement = JsonNull
            override suspend fun close() {}
        }

        val result = fakeServer.listAllTools()

        assertEquals(2, callCount)
        assertEquals(3, result.tools.size)
        assertEquals("tool1", (result.tools[0] as JsonPrimitive).content)
        assertEquals("tool2", (result.tools[1] as JsonPrimitive).content)
        assertEquals("tool3", (result.tools[2] as JsonPrimitive).content)
    }
}
