package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.CallToolResult
import io.github.yeyi.agent.mcp.ClientInfo
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.JsonRpcRequest
import io.github.yeyi.agent.mcp.JsonRpcResponse
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.McpMethods
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.McpTransport
import io.github.yeyi.agent.mcp.ToolDef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 记录 TOOLS_CALL 参数与 close 调用的假 MCP 传输。 */
private class RecordingTransport : McpTransport {
    val calledTools: MutableList<String> = mutableListOf()
    var closed: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> =
        when (request.method) {
            McpMethods.INITIALIZE -> JsonRpcResponse(
                jsonrpc = "2.0",
                id = request.id,
                result = json.parseToJsonElement(
                    """{"protocolVersion":"2025-06-18","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}"""
                ),
            )

            McpMethods.TOOLS_LIST -> JsonRpcResponse(
                jsonrpc = "2.0",
                id = request.id,
                result = json.encodeToJsonElement(
                    ListToolsResult.serializer(),
                    ListToolsResult(
                        tools = listOf(
                            ToolDef("add", "add two numbers", buildJsonObject { put("type", JsonPrimitive("object")) })
                        )
                    ),
                ),
            )

            McpMethods.TOOLS_CALL -> {
                calledTools += request.params
                    ?.let { json.decodeFromJsonElement(CallToolParams.serializer(), it) }
                    ?.name
                    ?: error("Missing params for tools/call")
                JsonRpcResponse(
                    jsonrpc = "2.0",
                    id = request.id,
                    result = json.encodeToJsonElement(
                        CallToolResult.serializer(),
                        CallToolResult(content = JsonPrimitive("42")),
                    ),
                )
            }

            else -> error("Unknown method: ${request.method}")
        }

    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) = Unit
    override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
    override suspend fun close() {
        closed = true
    }
}

private fun calcMcp(transport: RecordingTransport): Mcp = object : Mcp() {
    override val name: String = "calc"
    override val description: String = "calc-desc"
    override val client: McpClient = McpClient(transport)
}

/** boss 首轮: 调 publish_task, selection = toolset("calc")。 */
private val BOSS_PUBLISH_TOOLSET: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(
        content = "",
        toolCalls = listOf(
            ToolCall(
                id = "c1",
                name = "publish_task",
                arguments = buildJsonObject {
                    put("query", "calc")
                    putJsonArray("tasks") {
                        add(
                            buildJsonObject {
                                put("ref", "calc_task")
                                put("selection", buildJsonObject {
                                    put("type", "toolset")
                                    put("name", "calc")
                                })
                                put("task", "add two numbers")
                            }
                        )
                    }
                },
            )
        ),
    ),
    usage = null,
    finishReason = FinishReason.ToolCalls,
)

/** worker(Horse): 调 MCP 远端工具 add。 */
private val WORKER_CALL_ADD: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(
        content = "",
        toolCalls = listOf(
            ToolCall(
                id = "w1",
                name = "add",
                arguments = buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                },
            )
        ),
    ),
    usage = null,
    finishReason = FinishReason.ToolCalls,
)

private val WORKER_FINAL: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(content = "42", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

private val BOSS_CONTINUATION: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(content = "结果如下: 42", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

/** worker 兜底形态(Ox): 无工具直接 LLM 回答。 */
private val OX_FINAL: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(content = "ox fallback", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

/**
 * mcps() 接通集成测试 — MCP 服务经 [BossAgentBuilder.mcps] 并入 worker 的
 * toolset 通道: boss 能力清单可见、worker 经 Selection.Toolset 派发执行远端
 * 工具、shutdown 经 onShutdown 钩子关闭 MCP 连接。
 *
 * boss 与 worker 共用同一 FakeLlmProvider,响应按消费顺序排列:
 * publish_task 短路首轮 → worker 调 add → worker Final → boss 续轮 Final。
 */
class BossAgentMcpIntegrationTest {

    @Test
    fun `worker executes MCP tool via toolset selection and shutdown closes the connection`() = runBlocking {
        val transport = RecordingTransport()
        val mcpRegistry = McpRegistry(ClientInfo("team-test", "1.0")).apply {
            register(calcMcp(transport))
        }

        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                BOSS_PUBLISH_TOOLSET,   // 1. boss 用户轮: publish_task (短路,本轮不再调)
                WORKER_CALL_ADD,        // 2. worker: 调 MCP 远端 add
                WORKER_FINAL,           // 3. worker: Final
                BOSS_CONTINUATION,      // 4. boss 续轮: Final
            )
        )

        val boss = bossAgent {
            llmProvider(llm)
            memory(io.github.yeyi.agent.memory.InMemoryMemory(), 20)
            maxIterations(5)
            mcps(mcpRegistry)
        }

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report += it }
        }

        boss.run(AgentQuery.text("帮我算加法")).toList()

        // worker 经 Selection.Toolset("calc") 派发 → Horse 拉取 MCP 工具 → 执行 add
        withTimeout(5000) {
            while (transport.calledTools.isEmpty()) delay(50)
        }
        assertEquals(listOf("add"), transport.calledTools)

        // 续轮完成后 shutdown → onShutdown → unregisterAll 关闭 MCP 连接
        withTimeout(5000) {
            while (report.none { it is AgentEvent.Final }) delay(50)
        }
        boss.shutdown()
        withTimeout(5000) {
            while (!transport.closed) delay(50)
        }
        assertTrue(transport.closed, "MCP connection must be closed after shutdown")

        contJob.cancel()
    }

    @Test
    fun `publish_task capability list includes registered MCP services`() = runBlocking {
        val transport = RecordingTransport()
        val mcpRegistry = McpRegistry(ClientInfo("team-test", "1.0")).apply {
            register(calcMcp(transport))
        }

        val llm = FakeLlmProvider(nonStreamResponses = listOf(BOSS_PUBLISH_TOOLSET))
        val boss = bossAgent {
            llmProvider(llm)
            memory(io.github.yeyi.agent.memory.InMemoryMemory(), 20)
            maxIterations(5)
            mcps(mcpRegistry)
        }

        boss.run(AgentQuery.text("帮我算加法")).toList()

        // boss 首轮看到的 publish_task 描述里, [toolset] 分组应含 MCP 服务 calc
        val publishTask = llm.recordedRequests.first().tools.single { it.name == "publish_task" }
        assertTrue("calc" in publishTask.description, "capability list should mention Mcp 'calc': ${publishTask.description}")
        assertTrue("calc-desc" in publishTask.description, "capability list should mention Mcp description")

        boss.shutdown()
    }

    @Test
    fun `mcps merges into existing toolsetRegistry without name clash`() = runBlocking {
        // toolsets + mcps 同时声明: MCP 服务与普通 Toolset 并入同一能力通道,互不冲突
        val transport = RecordingTransport()
        val mcpRegistry = McpRegistry(ClientInfo("team-test", "1.0")).apply {
            register(calcMcp(transport))
        }
        val toolsetReg = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(io.github.yeyi.agent.toolset.Toolset("weather", "weather-desc").apply { add(stubTool("get_weather")) })
        }

        val llm = FakeLlmProvider(nonStreamResponses = listOf(BOSS_PUBLISH_TOOLSET))
        val boss = bossAgent {
            llmProvider(llm)
            memory(io.github.yeyi.agent.memory.InMemoryMemory(), 20)
            maxIterations(5)
            toolsets(toolsetReg)
            mcps(mcpRegistry)
        }

        boss.run(AgentQuery.text("帮我算加法")).toList()

        val publishTask = llm.recordedRequests.first().tools.single { it.name == "publish_task" }
        assertTrue("calc" in publishTask.description, "merged Mcp 'calc' should be visible: ${publishTask.description}")
        assertTrue("weather" in publishTask.description, "toolset 'weather' should be visible: ${publishTask.description}")

        boss.shutdown()
    }

    @Test
    fun `manual unregisterAll while boss alive prevents MCP reactivation`() = runBlocking {
        // boss 存活期间手动 unregisterAll: toolsetRegistry0 里的 Mcp 按名移除,
        // 再派发 calc selection 落空 → assembleHorse 抛 ISE → Ox 兜底,不触达死连接。
        val transport = RecordingTransport()
        val mcpRegistry = McpRegistry(ClientInfo("team-test", "1.0")).apply {
            register(calcMcp(transport))
        }

        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                BOSS_PUBLISH_TOOLSET,   // 1. boss 用户轮: publish_task (calc 可用)
                WORKER_CALL_ADD,        // 2. worker(Horse): 调 MCP 远端 add
                WORKER_FINAL,           // 3. worker: Final
                BOSS_CONTINUATION,      // 4. boss 续轮: Final
                BOSS_PUBLISH_TOOLSET,   // 5. boss 第二用户轮: 仍选 calc (capability 描述静态)
                OX_FINAL,               // 6. worker(Ox 兜底): 无工具直接回答 → TaskUpdate Final
                BOSS_CONTINUATION,      // 7. boss 续轮: 汇总 Final
            )
        )

        val boss = bossAgent {
            llmProvider(llm)
            memory(io.github.yeyi.agent.memory.InMemoryMemory(), 20)
            maxIterations(5)
            mcps(mcpRegistry)
        }

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report += it }
        }

        // 第一轮: MCP 正常执行
        boss.run(AgentQuery.text("帮我算加法")).toList()
        withTimeout(5000) {
            while (transport.calledTools.isEmpty()) delay(50)
        }
        withTimeout(5000) {
            while (report.none { it is AgentEvent.Final }) delay(50)
        }

        // 宿主存活期间手动注销 — 共享 toolsetRegistry0 的 calc 条目同步移除
        mcpRegistry.unregisterAll().join()

        // 第二轮: 再派发 calc → 已移除 → Ox 兜底,不再触达 MCP 连接
        boss.run(AgentQuery.text("再算一次")).toList()
        withTimeout(5000) {
            while (report.count { it is AgentEvent.Final } < 2) delay(50)
        }

        assertEquals(listOf("add"), transport.calledTools, "no further MCP calls after manual unregisterAll")

        boss.shutdown()
        contJob.cancel()
    }

    private fun stubTool(name: String) = object : io.github.yeyi.agent.tool.Tool {
        override val name: String = name
        override val description: String = "stub"
        override val parametersSchema: io.github.yeyi.agent.tool.ToolParameters =
            io.github.yeyi.agent.tool.ToolParameters.Empty
        override suspend fun execute(
            arguments: JsonElement,
            context: io.github.yeyi.agent.tool.ToolExecutionContext,
        ): io.github.yeyi.agent.tool.ToolExecutionResult = io.github.yeyi.agent.tool.ToolExecutionResult.success("ok")
    }
}
