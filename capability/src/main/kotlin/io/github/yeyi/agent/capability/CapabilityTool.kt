package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement

internal class CapabilityTool<Ctx : CapabilityContext>(
    capabilityName: String,
    private val capability: Capability<Ctx>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val argumentsSchema: ToolParameters = ToolParameters.Empty,
) : Tool {
    override val name: String = "${capabilityName}_${capability.name}"
    override val description: String = capability.description
    override val parametersSchema: ToolParameters get() = argumentsSchema

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        return ToolExecutionResult(
            capability.activate(arguments, capabilityContextFactory.create(context))
        )
    }
}