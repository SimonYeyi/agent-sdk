package io.github.yeyi.agent.skill

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry

/**
 * Skill 的注册中心，复用 [DefaultCapabilityRegistry] 的逻辑。
 */
public class SkillRegistry :
    CapabilityRegistry<Skill, Unit, SkillContext> by DefaultCapabilityRegistry(
    capabilityType = Skill.CAPABILITY_TYPE
)
