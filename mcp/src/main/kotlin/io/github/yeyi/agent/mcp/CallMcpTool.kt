package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tool for calling an MCP tool on a registered server.
 */
internal class CallMcpTool(private val registry: McpServerRegistry) : Tool {
    override val name: String = "call_mcp"

    override val description: String = "当需要调用MCP工具时通过本工具代理调用。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "server_name": { "type": "string" },
                "params": {
                    "type": "object",
                    "description": "MCP protocol tools/call params",
                    "properties": {
                        "name": { "type": "string" },
                        "arguments": { "type": "object" }
                    },
                    "required": ["name", "arguments"]
                }
            },
            "required": ["server_name", "params"]
        }
    """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val argsObj = arguments.jsonObject

        val serverName = argsObj["server_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult(content = "Missing server_name", isError = true)

        val params = argsObj["params"]
            ?: return ToolExecutionResult(content = "Missing params", isError = true)

        val result = registry.callTool(serverName, params)

        return ToolExecutionResult(content = result)
    }
}