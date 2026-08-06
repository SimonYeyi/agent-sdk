package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool

public abstract class CapabilityAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    protected val registry: CapabilityRegistry<Ctx, C, T>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val arguments: CapabilityArguments<T>?
) {
    protected abstract fun adapt(): List<Tool>

    /**
     * 将 adapter 产生的 Tool 注册到 [AgentBuilder]。
     *
     * 内部调用 [adapt] 生成 Tool 列表，然后逐个通过 [AgentBuilder.tool] 注册。
     */
    public fun installOn(agentBuilder: AgentBuilder): Unit =
        adapt().forEach { agentBuilder.tool(it) }

    public companion object {
        /**
         * 创建 CapabilityAdapter 实例。
         *
         * @param enableDelegateAdaptMode true 使用委托模式（单一 Delegate Tool），false 使用一一映射模式（每个 Capability 对应一个 Tool）
         */
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
                    registry.capabilityType,
                    cap,
                    capabilityContextFactory,
                    arguments
                )
            }
}
