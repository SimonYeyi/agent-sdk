package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class CapabilityTool<Ctx : CapabilityContext, T : Any>(
    capabilityName: String,
    private val capability: Capability<T, Ctx>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val arguments: CapabilityArguments<T>?
) : Tool {
    override val name: String = "${capabilityName}_${capability.name}"
    override val description: String = capability.description
    override val parametersSchema: ToolParameters
        get() = arguments?.let {
            ToolParameters.JsonSchema(it.schema)
        } ?: ToolParameters.Empty

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val input = this.arguments?.let {
            Json.decodeFromJsonElement(it.serializer, arguments)
        }
        return ToolExecutionResult(
            capability.activate(input, capabilityContextFactory.create(context))
        )
    }
}