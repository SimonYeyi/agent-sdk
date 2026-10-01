package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * LazyTool 的接线模板 —— 实现全部 4 个接线方法。
 *
 * 仅供 LazyTool 模块内部 `lazyTools(registry)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数，不感知本类。
 */
internal class LazyToolPlugin(
    private val registry: LazyToolRegistry,
    enableDelegateAdaptMode: Boolean = true,
) : CapabilityPlugin<LazyTool, Unit, LazyToolContext>(registry, enableDelegateAdaptMode) {

    override fun contextFactory(): LazyToolContextFactory = LazyToolContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(LazyToolCaller(registry))
}
