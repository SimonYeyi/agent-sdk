package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.toolset.Toolset
import io.github.yeyi.agent.toolset.ToolsetContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 能力 — 一个 [Toolset] 形态的远端 MCP 服务。
 *
 * 本接口继承 [Toolset],接入 toolset 框架(委托模式 `load_toolset` 或
 * 一一映射模式 `toolset_<name>`),由 [McpRegistry] 统一管理,经 [mcps] DSL
 * 挂载到 Agent。
 *
 * 子 Tool **不**通过 [add] 注入——MCP 服务的工具列表是动态的,由 [client] 与
 * 远端 MCP server 协商产生。`load_toolset` 触发时通过 [definitions] 拉取
 * [toolsList] 并按 Toolset 统一格式渲染;`sub_tool_delegate` 触发时由 [dispatch]
 * 把子工具名 + 参数包装成 MCP `tools/call` 信封交给 [client]。
 *
 * 使用方式:
 * ```kotlin
 * class MyMcp(httpClient: HttpClient) : Mcp {
 *     override val name = "my_service"
 *     override val description = "我的 MCP 服务，提供 xxx 能力"
 *     override val client = McpClient(SseTransport("https://...", httpClient = httpClient))
 * }
 * ```
 */
public interface Mcp : Toolset {
    /** MCP 客户端实现，负责与远端或本地 MCP 服务进行协议通信。 */
    public val client: McpClient

    /**
     * MCP 子工具是动态管理的，调用本方法抛 [UnsupportedOperationException]。
     * 静态子 Tool 集合请直接使用 [io.github.yeyi.agent.toolset.Toolset]。
     */
    override fun add(tool: Tool) {
        throw UnsupportedOperationException(
            "Mcp '$name' tools are dynamic, managed by the MCP server"
        )
    }

    /** MCP 子工具是动态管理的，调用本方法抛 [UnsupportedOperationException]。 */
    override fun add(tools: Iterable<Tool>) {
        throw UnsupportedOperationException(
            "Mcp '$name' tools are dynamic, managed by the MCP server"
        )
    }

    override fun definitions(): JsonElement = runBlocking { client.toolsList().tools }

    /**
     * `sub_tool_delegate` 触发时调用,把 `[name, arguments]` 包装成 MCP `tools/call`
     * 信封交给 [client] 执行。
     */
    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val envelope = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        return ToolExecutionResult.success(client.callTool(envelope).toString())
    }
}
