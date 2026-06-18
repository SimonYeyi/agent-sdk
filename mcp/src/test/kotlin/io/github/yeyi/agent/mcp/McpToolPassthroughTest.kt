package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
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
        private val listAllToolsResult: JsonElement = buildJsonObject {
            put("tools", kotlinx.serialization.json.JsonArray(emptyList()))
        },
    ) : McpServer {
        override val transport: McpTransport = NoopTransport
        override suspend fun initialize(): InitializeResult = InitializeResult(
            protocolVersion = "",
            serverInfo = ServerInfo(name = "", version = ""),
        )
        override suspend fun listTools(cursor: String?): JsonElement = listAllToolsResult
        suspend fun listAllTools(): JsonElement = listAllToolsResult
        override suspend fun ping(): Boolean = true
        override suspend fun callTool(params: JsonElement): JsonElement = toolCallResult
        override suspend fun close() = Unit
    }

    private object NoopTransport : McpTransport {
        override suspend fun send(request: JsonRpcRequest): JsonRpcResponse<JsonElement> =
            error("not used")
        override suspend fun sendNotification(request: JsonRpcRequest) = Unit
        override val notifications: Flow<JsonElement> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `CallMcpTool maps result isError true to ToolExecutionResult isError true`() = runTest {
        val contentArray = kotlinx.serialization.json.JsonArray(emptyList())
        val result = buildJsonObject {
            put("content", contentArray)
            put("isError", true)
        }
        val registry = McpServerRegistry().register(
            FakeServer(name = "fake", description = "fake", toolCallResult = result)
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

        assertTrue(output.isError)
        assertEquals(contentArray.toString(), output.content)
    }

    @Test
    fun `CallMcpTool maps result isError false to ToolExecutionResult isError false`() = runTest {
        val contentArray = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("ok")))
        val result = buildJsonObject {
            put("content", contentArray)
            put("isError", false)
        }
        val registry = McpServerRegistry().register(
            FakeServer(name = "fake", description = "fake", toolCallResult = result)
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
    fun `CallMcpTool defaults isError to false when field is absent`() = runTest {
        val result = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("ok"))))
        }
        val registry = McpServerRegistry().register(
            FakeServer(name = "fake", description = "fake", toolCallResult = result)
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
        assertEquals("[${JsonPrimitive("ok")}]", output.content)
    }

    @Test
    fun `CallMcpTool defaults isError to false when field is non-boolean`() = runTest {
        val result = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(emptyList()))
            put("isError", "not a boolean")
        }
        val registry = McpServerRegistry().register(
            FakeServer(name = "fake", description = "fake", toolCallResult = result)
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
    }

    @Test
    fun `LoadMcpTool passes through listAllTools result as content`() = runTest {
        val expected = buildJsonObject {
            put("tools", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("x"))))
        }
        val registry = McpServerRegistry().register(
            FakeServer(
                name = "fake",
                description = "fake",
                listAllToolsResult = expected,
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
}
