package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool

public abstract class CapabilityAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    protected val registry: CapabilityRegistry<Ctx, C, T>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val arguments: CapabilityArguments<T>?
) {
    protected abstract fun adapt(): List<Tool>

    public fun installOn(agentBuilder: AgentBuilder): Unit =
        adapt().forEach { agentBuilder.tool(it) }
}

public class DelegationAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
    override fun adapt(): List<Tool> =
        listOf(LoadCapabilityTool(registry, capabilityContextFactory, arguments))
}

public class OneToOneAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
    override fun adapt(): List<Tool> =
        registry.all()
            .map { cap ->
                CapabilityTool(
                    registry.capabilityName,
                    cap,
                    capabilityContextFactory,
                    arguments
                )
            }
}
