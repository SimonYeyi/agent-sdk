package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityPlugin

/**
 * Subagent 的接线模板 —— 仅 override contextFactory 和 arguments,无辅助 tool。
 *
 * 仅供 Subagent 模块内部 `subagents(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class SubagentPlugin(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true,
) : CapabilityPlugin<Subagent, SubagentTask, SubagentContext>(registry, enableDelegateAdaptMode) {

    override fun contextFactory(): SubagentContextFactory = SubagentContextFactory()

    override fun arguments(): SubagentArguments = SubagentArguments()
}
