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

    public companion object {
        public fun <Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any> of(
            registry: CapabilityRegistry<Ctx, C, T>,
            capabilityContextFactory: CapabilityContextFactory<Ctx>,
            arguments: CapabilityArguments<T>?,
            enableDelegateAdaptMode: Boolean = true
        ): CapabilityAdapter<Ctx, C, T> = if (enableDelegateAdaptMode) {
            DelegationAdapter(registry, capabilityContextFactory, arguments)
        } else {
            OneToOneAdapter(registry, capabilityContextFactory, arguments)
        }
    }
}

private class DelegationAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
    override fun adapt(): List<Tool> =
        listOf(CapabilityLoadTool(registry, capabilityContextFactory, arguments))
}

private class OneToOneAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
    override fun adapt(): List<Tool> =
        registry.all()
            .map { cap ->
                CapabilityAdaptTool(
                    registry.capabilityName,
                    cap,
                    capabilityContextFactory,
                    arguments
                )
            }
}
