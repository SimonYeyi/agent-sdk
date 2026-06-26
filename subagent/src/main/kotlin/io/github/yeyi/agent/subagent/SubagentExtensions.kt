package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * 把已有 registry 挂到 AgentBuilder。
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
