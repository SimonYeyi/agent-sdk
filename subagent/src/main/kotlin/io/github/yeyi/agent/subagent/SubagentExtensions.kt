package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * 把已有 registry 挂到 AgentBuilder。
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    mode: CapabilityAdapter.Mode = CapabilityAdapter.Mode.Delegate,
) {
    CapabilityAdapter.of(
        registry,
        SubagentContextFactory(),
        SubagentArguments(),
        mode
    ).installOn(this)
}
