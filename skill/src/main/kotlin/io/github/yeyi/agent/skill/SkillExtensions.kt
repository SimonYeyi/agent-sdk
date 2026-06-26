package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * Register multiple [Skill]s by [SkillRegistry]
 */
public fun AgentBuilder.skills(
    registry: SkillRegistry,
    enableDelegateAdaptMode: Boolean = true
) {
    CapabilityAdapter.of(
        registry,
        SkillContextFactory(),
        null,
        enableDelegateAdaptMode
    ).installOn(this)
}
