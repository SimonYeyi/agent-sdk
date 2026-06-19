package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ListAllToolsTest {
    private object NoopTransport : McpTransport {
        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> =
            error("not used")
        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `aggregates multiple pages`() = runTest {
        val page1 = ListToolsResult(
            tools = JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2"))),
            nextCursor = "page2",
        )
        val page2 = ListToolsResult(
            tools = JsonArray(listOf(JsonPrimitive("tool3"))),
            nextCursor = null,
        )
        var callCount = 0
        val fakeServer = object : McpServer {
            override val name: String = "test"
            override val description: String = "test"
            override val transport: McpTransport = NoopTransport
            override suspend fun initialize(): InitializeResult = InitializeResult(
                protocolVersion = "",
                serverInfo = ServerInfo(name = "", version = ""),
                capabilities = ServerCapabilities(),
            )
            override suspend fun listTools(cursor: String?): ListToolsResult {
                callCount++
                return if (cursor == null) page1 else page2
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

    @Test
    fun `returns single page when no cursor`() = runTest {
        val tools = JsonArray(listOf(JsonPrimitive("tool1"), JsonPrimitive("tool2")))
        val fakeServer = object : McpServer {
            override val name: String = "test"
            override val description: String = "test"
            override val transport: McpTransport = NoopTransport
            override suspend fun initialize(): InitializeResult = InitializeResult(
                protocolVersion = "",
                serverInfo = ServerInfo(name = "", version = ""),
                capabilities = ServerCapabilities(),
            )
            override suspend fun listTools(cursor: String?): ListToolsResult =
                ListToolsResult(tools = tools, nextCursor = null)
            override suspend fun ping(): Boolean = true
            override suspend fun callTool(params: JsonElement): JsonElement = JsonNull
            override suspend fun close() {}
        }

        val result = fakeServer.listAllTools()

        assertEquals(2, result.tools.size)
        assertEquals("tool1", (result.tools[0] as JsonPrimitive).content)
        assertEquals("tool2", (result.tools[1] as JsonPrimitive).content)
    }
}
