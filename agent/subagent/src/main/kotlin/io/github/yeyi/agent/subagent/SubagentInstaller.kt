package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityInstaller

/**
 * Subagent 的接线模板 —— 仅 override contextFactory 和 arguments,无辅助 tool。
 *
 * 仅供 Subagent 模块内部 `subagents(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class SubagentInstaller(
    private val registry: SubagentRegistry,
) : CapabilityInstaller<Subagent, SubagentTask, SubagentContext>() {

    override fun registry(): SubagentRegistry = registry

    override fun contextFactory(): SubagentContextFactory = SubagentContextFactory()

    override fun arguments(): SubagentArguments = SubagentArguments()
}
