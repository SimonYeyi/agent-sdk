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
internal class LoadMcpTool(private val registry: McpRegistry) : Tool {
    override val name: String = "load_mcp"

    override val description: String by lazy { buildDescription() }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "mcp_name": { "type": "string" }
            },
            "required": ["mcp_name"]
        }
    """.trimIndent()
    )

    private fun buildDescription(): String = """
        |当需要使用以下 MCP ，调用本工具：
        |${registry.buildDescription()}
    """.trimMargin()

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val mcpName = arguments.jsonObject["mcp_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: throw IllegalArgumentException("Missing mcp_name")

        val toolsList = registry.toolsList(mcpName)
        return ToolExecutionResult.success("发现以下可用 MCP 工具：\n$toolsList")
    }
}