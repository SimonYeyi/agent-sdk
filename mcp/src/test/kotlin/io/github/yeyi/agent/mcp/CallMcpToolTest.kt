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
    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in CallMcpToolTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("not used")))
    }

    private fun stubContext(): ToolContext = ToolContext(
        toolCallId = "test",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = StubLlm,
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    private fun createFakeMcp(
        name: String = "test",
        toolCallResult: JsonElement = JsonNull,
        toolCallError: Boolean = false,
    ): Mcp = object : Mcp {
        override val name: String = name
        override val description: String = "test"
        override val client: McpClient = McpClient(FakeServerTransport(toolCallResult, toolCallError))
    }

    private class FakeServerTransport(
        private val toolCallResult: JsonElement,
        private val toolCallError: Boolean,
    ) : McpTransport {
        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
            return when (request.method) {
                McpMethods.INITIALIZE -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"protocolVersion":"2025-06-18","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}"""
                    ),
                )
                McpMethods.TOOLS_LIST -> JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = kotlinx.serialization.json.Json.parseToJsonElement("""{"tools":[]}"""),
                )
                McpMethods.TOOLS_CALL -> {
                    if (toolCallError) {
                        JsonRpcResponse(
                            jsonrpc = "2.0",
                            id = request.id,
                            result = kotlinx.serialization.json.Json.encodeToJsonElement(
                                CallToolResult.serializer(),
                                CallToolResult(content = toolCallResult, isError = true),
                            ),
                        )
                    } else {
                        JsonRpcResponse(
                            jsonrpc = "2.0",
                            id = request.id,
                            result = kotlinx.serialization.json.Json.encodeToJsonElement(
                                CallToolResult.serializer(),
                                CallToolResult(content = toolCallResult),
                            ),
                        )
                    }
                }
                else -> error("Unknown method: ${request.method}")
            }
        }

        override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) = Unit
        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `throws exception when isError true`() = runTest {
        val content = JsonArray(listOf(JsonPrimitive("error result")))
        val registry = McpRegistry(ClientInfo("", "")).register(
            createFakeMcp(
                toolCallResult = content,
                toolCallError = true,
            )
        )
        val tool = CallMcpTool(registry)

        val exception = assertFailsWith<RuntimeException> {
            tool.execute(
                arguments = buildJsonObject {
                    put("mcp_name", "test")
                    putJsonObject("params") {
                        put("name", "test_tool")
                        put("arguments", JsonObject(emptyMap()))
                    }
                },
                context = stubContext(),
            )
        }

        assertTrue(exception.message?.contains("MCP Exception") == true)
    }

    @Test
    fun `returns content when isError false`() = runTest {
        val content = JsonArray(listOf(JsonPrimitive("ok")))
        val registry = McpRegistry(ClientInfo("", "")).register(
            createFakeMcp(
                toolCallResult = content,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("mcp_name", "test")
                putJsonObject("params") {
                    put("name", "test_tool")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = stubContext(),
        )

        assertFalse(output.isError)
        assertEquals(content.toString(), output.content)
    }

    @Test
    fun `returns null string when content absent`() = runTest {
        val registry = McpRegistry(ClientInfo("", "")).register(
            createFakeMcp(
                toolCallResult = JsonNull,
                toolCallError = false,
            )
        )
        val tool = CallMcpTool(registry)

        val output = tool.execute(
            arguments = buildJsonObject {
                put("mcp_name", "test")
                putJsonObject("params") {
                    put("name", "test_tool")
                    put("arguments", JsonObject(emptyMap()))
                }
            },
            context = stubContext(),
        )

        assertFalse(output.isError)
        assertEquals("{}", output.content)
    }
}
