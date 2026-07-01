package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter
import io.github.yeyi.agent.log.LoggingTagged

/**
 * 将 SubagentRegistry 注册到 Agent。
 *
 * @param registry Subagent 注册中心
 * @param enableDelegateAdaptMode true 使用委托模式，false 为每个 subagent 生成独立工具
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true
) {
    CapabilityAdapter.of(
        registry,
        SubagentContextFactory(),
        SubagentArguments(),
        enableDelegateAdaptMode
    ).installOn(this)
}

internal val log = LoggingTagged("hook")