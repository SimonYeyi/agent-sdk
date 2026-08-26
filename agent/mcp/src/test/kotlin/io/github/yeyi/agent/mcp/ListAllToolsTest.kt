package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ListAllToolsTest {

    private class FakePaginatedTransport(
        private val pages: List<ListToolsResult>,
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
                McpMethods.TOOLS_LIST -> {
                    val cursor = request.params?.let {
                        json.decodeFromJsonElement(ListToolsParams.serializer(), it).cursor
                    }
                    val index = if (cursor == null) 0 else cursor.toInt()
                    val result = pages[index]
                    JsonRpcResponse(
                        jsonrpc = "2.0",
                        id = request.id,
                        result = json.encodeToJsonElement(ListToolsResult.serializer(), result),
                    )
                }
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `aggregates multiple pages`() = runTest {
        val page1 = ListToolsResult(
            tools = listOf(
                ToolDef("tool1", "d1", buildJsonObject { put("type", JsonPrimitive("object")) }),
                ToolDef("tool2", "d2", buildJsonObject { put("type", JsonPrimitive("object")) }),
            ),
            nextCursor = "1",
        )
        val page2 = ListToolsResult(
            tools = listOf(ToolDef("tool3", "d3", buildJsonObject { put("type", JsonPrimitive("object")) })),
            nextCursor = null,
        )
        val client = McpClient(FakePaginatedTransport(listOf(page1, page2)))

        val result = client.toolsList()

        assertEquals(3, result.tools.size)
        assertEquals("tool1", result.tools[0].name)
        assertEquals("tool2", result.tools[1].name)
        assertEquals("tool3", result.tools[2].name)
    }

    @Test
    fun `returns single page when no cursor`() = runTest {
        val tools = listOf(
            ToolDef("tool1", "d1", buildJsonObject { put("type", JsonPrimitive("object")) }),
            ToolDef("tool2", "d2", buildJsonObject { put("type", JsonPrimitive("object")) }),
        )
        val client = McpClient(FakePaginatedTransport(listOf(ListToolsResult(tools = tools, nextCursor = null))))

        val result = client.toolsList()

        assertEquals(2, result.tools.size)
        assertEquals("tool1", result.tools[0].name)
        assertEquals("tool2", result.tools[1].name)
    }
}
