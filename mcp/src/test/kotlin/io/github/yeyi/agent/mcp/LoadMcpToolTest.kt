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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadMcpToolTest {
    private object NoopTransport : McpTransport {
        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> =
            error("not used")
        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    private fun createFakeServer(
        name: String = "test",
        description: String = "test",
        listToolsResult: ListToolsResult = ListToolsResult(tools = JsonArray(emptyList())),
    ): McpServer = object : McpServer {
        override val name: String = name
        override val description: String = description
        override val transport: McpTransport = NoopTransport
        override suspend fun initialize(): InitializeResult = InitializeResult(
            protocolVersion = "",
            serverInfo = ServerInfo(name = "", version = ""),
            capabilities = ServerCapabilities(),
        )
        override suspend fun listTools(cursor: String?): ListToolsResult = listToolsResult
        override suspend fun ping(): Boolean = true
        override suspend fun callTool(params: JsonElement): JsonElement = JsonNull
        override suspend fun close() = Unit
    }

    @Test
    fun `returns tools array as content`() = runTest {
        val tools = JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2")))
        val registry = McpServerRegistry(ClientInfo("", "")).register(
            createFakeServer(
                listToolsResult = ListToolsResult(tools = tools),
            )
        )
        val tool = LoadMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject { put("server_name", "test") },
            context = ToolContext(toolCallId = "test"),
        )

        assertFalse(output.isError)
        assertEquals(tools.toString(), output.content)
    }

    @Test
    fun `description contains registered server name`() = runTest {
        val registry = McpServerRegistry(ClientInfo("", "")).register(
            createFakeServer(
                name = "my-server",
                description = "my server description",
            )
        )
        val tool = LoadMcpTool(registry)

        assertTrue(tool.description.contains("my-server"))
        assertTrue(tool.description.contains("my server description"))
    }
}
