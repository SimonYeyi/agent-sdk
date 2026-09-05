package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One-to-one mode Tool: exposes a single [Capability] as its own Tool.
 *
 * The tool name follows the pattern `{capabilityType}_{capabilityName}`.
 * The description and parameters schema come directly from the capability.
 *
 * @param Ctx the capability-specific context type
 * @param T the arguments type
 */
internal class CapabilityAdaptTool<Ctx : CapabilityContext, T : Any>(
    capabilityType: String,
    private val capability: Capability<T, Ctx>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val arguments: CapabilityArguments<T>?
) : Tool {
    override val name: String = "${capabilityType}_${capability.name}"
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
        return ToolExecutionResult.success(
            capability.activate(input, capabilityContextFactory.create(context))
        )
    }
}
