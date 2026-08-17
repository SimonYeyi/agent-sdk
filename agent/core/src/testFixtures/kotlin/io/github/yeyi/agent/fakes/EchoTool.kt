package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EchoTool(
    override val name: String = "echo",
    override val description: String = "Echoes back the given text",
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"text":{"type":"string"}}}"""
    )
) : Tool {
    val invocations: MutableList<JsonElement> = mutableListOf()
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        invocations += arguments
        val text = (arguments as? JsonObject)?.get("text")
            ?.let { it as? JsonPrimitive }?.content ?: arguments.toString()
        return ToolExecutionResult.success(text)
    }
}
