package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry

/**
 * Subagent 的注册中心，复用 [DefaultCapabilityRegistry] 的逻辑。
 */
public class SubagentRegistry :
    CapabilityRegistry<SubagentContext, Subagent, SubagentTask> by DefaultCapabilityRegistry(
        capabilityName = Subagent.NAME
    )
