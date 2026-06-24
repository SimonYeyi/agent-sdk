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

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in LoadMcpToolTest")
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
            context = stubContext(),
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
