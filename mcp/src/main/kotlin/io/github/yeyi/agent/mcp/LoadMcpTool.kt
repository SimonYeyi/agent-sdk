package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tool for discovering available MCP tools from registered servers.
 */
internal class LoadMcpTool(private val registry: McpServerRegistry) : Tool {
    override val name: String = "load_mcp_tools"

    override val description: String = buildDescription()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "server_name": { "type": "string" }
            },
            "required": ["server_name"]
        }
    """.trimIndent()
    )

    private fun buildDescription(): String = """
        |当需要激活以下MCP Server时，调用本工具：
        |${registry.buildDescription()}
    """.trimMargin()

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val serverName = arguments.jsonObject["server_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: throw IllegalArgumentException("Missing server_name")

        val result = registry.listTools(serverName)

        return ToolExecutionResult(content = "$serverName MCP Server 已激活，可用工具如下：\n$result")
    }
}