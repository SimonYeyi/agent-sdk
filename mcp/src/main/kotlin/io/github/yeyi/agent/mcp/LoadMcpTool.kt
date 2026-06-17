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

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val serverName = arguments.jsonObject["server_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult(content = "Missing server_name", isError = true)

        val result = registry.listTools(serverName)

        return ToolExecutionResult(content = "$serverName MCP server tools:\n$result")
    }

    private fun buildDescription(): String = """
        |当需要激活以下MCP Server时，调用本工具：
        |${registry.buildDescription()}
    """.trimMargin()
}