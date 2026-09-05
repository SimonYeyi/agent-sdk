package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool

internal abstract class CapabilityAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    protected val registry: CapabilityRegistry<C, T, Ctx>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val arguments: CapabilityArguments<T>?
) {
    protected abstract fun adapt(): List<Tool>

    /**
     * 将 adapter 产生的 Tool 注册到 [io.github.yeyi.agent.AgentPluginContext]。
     *
     * 内部调用 [adapt] 生成 Tool 列表，然后逐个通过 [registerTool] 注册。
     */
    fun installOn(registerTool: (Tool) -> Unit): Unit =
        adapt().forEach { registerTool(it) }

    companion object {
        /**
         * 创建 CapabilityAdapter 实例。
         *
         * @param enableDelegateAdaptMode true 使用委托模式（单一 Delegate Tool），false 使用一一映射模式（每个 Capability 对应一个 Tool）
         */
        fun <C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext> of(
            registry: CapabilityRegistry<C, T, Ctx>,
            capabilityContextFactory: CapabilityContextFactory<Ctx>,
            arguments: CapabilityArguments<T>?,
            enableDelegateAdaptMode: Boolean = true
        ): CapabilityAdapter<C, T, Ctx> = if (enableDelegateAdaptMode) {
            DelegationAdapter(registry, capabilityContextFactory, arguments)
        } else {
            OneToOneAdapter(registry, capabilityContextFactory, arguments)
        }
    }
}

private class DelegationAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    registry: CapabilityRegistry<C, T, Ctx>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<C, T, Ctx>(registry, capabilityContextFactory, arguments) {
    override fun adapt(): List<Tool> =
        listOf(CapabilityLoadTool(registry, capabilityContextFactory, arguments))
}

private class OneToOneAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    registry: CapabilityRegistry<C, T, Ctx>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<C, T, Ctx>(registry, capabilityContextFactory, arguments) {
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
