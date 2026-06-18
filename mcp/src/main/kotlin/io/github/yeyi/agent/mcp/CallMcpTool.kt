package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

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
                    "description": "MCP protocol tools/call params",
                    "properties": {
                        "name": {
                            "type": "string",
                            "minLength": 1,
                            "description": "The exact registered name of the MCP tool to invoke on the target server (e.g. 'get_weather')."
                        },
                        "arguments": {
                            "type": "object",
                            "description": "Arguments object to pass to the tool. Its shape MUST match the target tool's input schema; pass an empty object {} if the tool takes no parameters."
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

        val serverName = argsObj.jsonObject["server_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: throw IllegalArgumentException("Missing server_name")

        val params = requireNotNull(argsObj["params"]) { "Missing params" }

        val result = registry.callTool(serverName, params)

        // Map MCP result.isError → SDK ToolExecutionResult.isError;
        // pass through result.content as the tool output (per MCP spec,
        // content is the canonical tool output channel).
        val isError = result.jsonObject["isError"]
            .let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: false
        val content = result.jsonObject["content"]?.toString() ?: ""

        return ToolExecutionResult(content = content, isError = isError)
    }
}