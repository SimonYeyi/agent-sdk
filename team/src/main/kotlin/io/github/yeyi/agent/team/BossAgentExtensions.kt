package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentWiring

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * @param wiring 接线配置，包含 memory 和各 registry
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(wiring: AgentWiring): BossAgent {
    return bossAgent {
        memory(wiring.memory, maxMemoryRounds)
        llmProvider(wiring.resolveLlmProvider(this@spawnBoss))
        maxIterations(maxIterations)
        persona(Persona(persona))
        wiring.resolveToolRegistry(this@spawnBoss)?.let { tools(it) }
        wiring.resolveLazyToolRegistry(this@spawnBoss)?.let { lazyTools(it) }
        wiring.resolveToolsetRegistry(this@spawnBoss)?.let { toolsets(it) }
        wiring.resolveSkillRegistry(this@spawnBoss)?.let { skills(it) }
        wiring.resolveSubagentRegistry(this@spawnBoss)?.let { subagents(it) }
        wiring.resolveMcpRegistry(this@spawnBoss, wiring.resolveToolsetRegistry(this@spawnBoss))?.let { mcps(it) }
    }
}
