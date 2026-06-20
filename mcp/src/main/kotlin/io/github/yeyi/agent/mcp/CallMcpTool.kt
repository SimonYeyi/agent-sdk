package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for calling an MCP tool on a registered server.
 */
internal class CallMcpTool(private val registry: McpServerRegistry) : Tool {
    override val name: String = "call_mcp_tool"

    override val description: String = "当需要调用 MCP 工具时通过本工具代理调用。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "server_name": { "type": "string" },
                "params": {
                    "type": "object",
                    "description": "MCP protocol 'tools/call' params.",
                    "properties": {
                        "name": {
                            "type": "string",
                            "description": "Target tool name from the MCP Server (for example: get_weather)."
                        },
                        "arguments": {
                            "type": "object",
                            "description": "Actual input schema of the target MCP tool. Replace this object with the specific parameters required by the tool."
                        }
                    },
                    "required": ["name", "arguments"],
                    "additionalProperties": false
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
            ?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing server_name")

        val params = requireNotNull(argsObj["params"]) { "Missing params" }

        val content = registry.callTool(serverName, params)

        return ToolExecutionResult(content = content.toString())
    }
}