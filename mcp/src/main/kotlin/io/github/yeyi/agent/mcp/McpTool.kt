package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 将 MCP 协议中的 [ToolDef] 包装为 agent [Tool]。
 *
 * 每个 [McpTool] 对应一个 MCP server 上声明的工具，[execute] 时将调用转发给
 * [McpClient.callTool] 完成实际执行。
 *
 * @param client 与目标 MCP server 通信的客户端
 * @param toolDef MCP 协议返回的工具元数据
 */
internal class McpTool(
    private val client: McpClient,
    private val toolDef: ToolDef,
) : Tool {

    override val name: String = toolDef.name

    override val description: String = toolDef.description ?: ""

    override val parametersSchema: ToolParameters =
        ToolParameters.JsonSchema(toolDef.inputSchema.toString())

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val params = CallToolParams(name = toolDef.name, arguments = arguments as? JsonObject)
        return ToolExecutionResult.success(client.callTool(params).toString())
    }
}
