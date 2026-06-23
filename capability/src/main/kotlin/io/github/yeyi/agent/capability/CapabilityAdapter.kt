package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolParameters

public abstract class CapabilityAdapter<Ctx : CapabilityContext, C : Capability<Ctx>>(
    protected val registry: CapabilityRegistry<Ctx, C>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val argumentsSchema: ToolParameters
) {
    protected abstract fun adapt(): List<Tool>

    public fun installOn(agentBuilder: AgentBuilder): Unit =
        adapt().forEach { agentBuilder.tool(it) }
}

public class DelegationAdapter<Ctx : CapabilityContext, C : Capability<Ctx>>(
    registry: CapabilityRegistry<Ctx, C>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    argumentsSchema: ToolParameters = ToolParameters.Empty,
) : CapabilityAdapter<Ctx, C>(registry, capabilityContextFactory, argumentsSchema) {
    override fun adapt(): List<Tool> =
        listOf(LoadCapabilityTool(registry, capabilityContextFactory, argumentsSchema))
}

public class OneToOneAdapter<Ctx : CapabilityContext, C : Capability<Ctx>>(
    registry: CapabilityRegistry<Ctx, C>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    argumentsSchema: ToolParameters = ToolParameters.Empty,
) : CapabilityAdapter<Ctx, C>(registry, capabilityContextFactory, argumentsSchema) {
    override fun adapt(): List<Tool> =
        registry.all()
            .map { cap ->
                CapabilityTool(
                    registry.capabilityName,
                    cap,
                    capabilityContextFactory,
                    argumentsSchema
                )
            }
}
