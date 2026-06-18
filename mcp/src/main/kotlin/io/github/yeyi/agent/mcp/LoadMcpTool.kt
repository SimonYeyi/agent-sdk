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

    override val description: String by lazy { buildDescription() }

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
        |当需要使用以下MCP Server时，调用本工具：
        |${registry.buildDescription()}
    """.trimMargin()

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val serverName = arguments.jsonObject["server_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: throw IllegalArgumentException("Missing server_name")

        val result = registry.listAllTools(serverName)

        return ToolExecutionResult(content = result.tools.toString())
    }
}