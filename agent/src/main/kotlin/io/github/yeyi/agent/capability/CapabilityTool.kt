package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement

internal class CapabilityTool<Ctx : CapabilityContext>(
    capabilityName: String,
    private val capability: Capability<Ctx>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>
) : Tool {
    override val name: String = "${capabilityName}_${capability.name}"
    override val description: String = capability.description
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        return ToolExecutionResult(capability.activate(capabilityContextFactory.create(context)))
    }
}