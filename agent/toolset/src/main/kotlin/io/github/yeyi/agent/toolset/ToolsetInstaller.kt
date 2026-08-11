package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityInstaller
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolDuplicateException

/**
 * Toolset 的接线模板 —— override installOn 套 try-catch 把 ToolDuplicateException 包装为 ToolsetsInstallException。
 *
 * 仅供 Toolset 模块内部 `toolsets(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class ToolsetInstaller(
    private val registry: ToolsetRegistry,
) : CapabilityInstaller<Toolset, Unit, ToolsetContext>() {

    override fun registry(): ToolsetRegistry = registry

    override fun contextFactory(): ToolsetContextFactory = ToolsetContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(SubToolDelegate(registry))

    public override fun installOn(
        agentBuilder: AgentBuilder,
        enableDelegateAdaptMode: Boolean,
    ) {
        try {
            super.installOn(agentBuilder, enableDelegateAdaptMode)
        } catch (e: ToolDuplicateException) {
            throw ToolsetsInstallException(e)
        }
    }
}