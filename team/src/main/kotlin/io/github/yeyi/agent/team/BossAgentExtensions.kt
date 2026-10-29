package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentScope

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * BossAgent 的角色由框架收敛（[BossAgentBuilder] 保留 [Persona.role]），因此 [AgentDefinition.persona]
 * 在 boss 场景不被消费，必须留空；外部增强请配置 [AgentDefinition.instructions]，
 * 经 [Persona.instructions] 写入 system prompt。
 *
 * @param scope 运行时上下文
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(scope: AgentScope): BossAgent {
    // 显式契约：boss 角色由框架定义，外部 persona 无落点，配置即违约 fail-fast。
    require(persona.isNullOrBlank()) {
        "BossAgent's role is owned by the framework — persona must be null or blank; use instructions to customize the boss."
    }
    val resolveInfo = scope.resolver().resolve(this@spawnBoss)
    return bossAgent {
        persona(Persona("").apply { instructions?.let { instructions(it) } })
        llmProvider(resolveInfo.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        scope.hook?.let { hook(it) }
        scope.modalityAdapter?.let { modalityAdapter(it) }
        resolveInfo.toolRegistry?.let { tools(it) }
        resolveInfo.lazyToolRegistry?.let { lazyTools(it) }
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
        resolveInfo.skillRegistry?.let { skills(it) }
        resolveInfo.subagentRegistry?.let { subagents(it) }
        resolveInfo.mcpRegistry?.let { mcps(it) }
    }
}
