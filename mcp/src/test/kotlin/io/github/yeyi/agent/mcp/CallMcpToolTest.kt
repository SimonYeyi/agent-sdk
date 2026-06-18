package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
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

class CallMcpToolTest {
    private object NoopTransport : McpTransport {
        override suspend fun <T> send(request: JsonRpcRequest<T>): JsonRpcResponse<JsonElement> =
            error("not used")
        override suspend fun <T> sendNotification(request: JsonRpcRequest<T>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    private fun createFakeServer(
        name: String = "test",
        toolCallResult: JsonElement = JsonNull,
        toolCallError: Boolean = false,
    ): McpServer = object : McpServer {
        override val name: String = name
        override val description: String = "test"
        override val transport: McpTransport = NoopTransport
        override suspend fun initialize(): InitializeResult = InitializeResult(
            protocolVersion = "",
            serverInfo = ServerInfo(name = "", version = ""),
        )
        override suspend fun listTools(cursor: String?): ListToolsResult =
            ListToolsResult(tools = JsonArray(emptyList()))
        override suspend fun ping(): Boolean = true
        override suspend fun callTool(params: JsonElement): JsonElement {
            if (toolCallError) {
                throw MCPServerException(toolCallResult.toString())
            }
            return toolCallResult
        }
        override suspend fun close() = Unit
    }

    @Test
    fun `throws exception when isError true`() = runTest {
        val content = JsonArray(listOf(JsonPrimitive("error result")))
        val registry = McpServerRegistry().register(
            createFakeServer(
                toolCallResult = content,
                toolCallError = true,
            )
        )
        val tool = CallMcpTool(registry)

        val exception = assertFailsWith<MCPServerException> {
            tool.execute(
                arguments = buildJsonObject {
                    put("server_name", "test")
                    putJsonObject("params") {
                        put("name", "test_tool")
                        put("arguments", JsonObject(emptyMap()))
                    }
                },
                context = ToolContext(toolCallId = "test"),
            )
        }

        assertTrue(exception.message?.contains(content.toString()) == true)
    }

    @Test
    fun `returns content when isError false`() = runTest {
        val content = JsonArray(listOf(JsonPrimitive("ok")))
        val registry = McpServerRegistry().register(
            createFakeServer(
                toolCallResult = content,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("server_name", "test")
                putJsonObject("params") {
                    put("name", "test_tool")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals(content.toString(), output.content)
    }

    @Test
    fun `returns null string when content absent`() = runTest {
        val registry = McpServerRegistry().register(
            createFakeServer(
                toolCallResult = JsonNull,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("server_name", "test")
                putJsonObject("params") {
                    put("name", "test_tool")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals("null", output.content)
    }
}
