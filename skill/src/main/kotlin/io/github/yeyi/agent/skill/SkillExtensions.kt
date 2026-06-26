package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * Register multiple [Skill]s by [SkillRegistry]
 */
public fun AgentBuilder.skills(
    registry: SkillRegistry,
    mode: CapabilityAdapter.Mode = CapabilityAdapter.Mode.Delegate
) {
    CapabilityAdapter.of(
        registry,
        SkillContextFactory(),
        null,
        mode
    ).installOn(this)
}
