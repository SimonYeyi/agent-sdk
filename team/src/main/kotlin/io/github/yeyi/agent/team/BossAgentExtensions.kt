package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentScope
import io.github.yeyi.agent.memory.Memory

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * BossAgent 的角色由框架收敛（[BossAgentBuilder] 保留 [AgentPersona.role]），因此 [AgentDefinition.persona]
 * 在 boss 场景不被消费，必须留空；外部增强请配置 [AgentDefinition.instructions]，
 * 经 [AgentPersona.instructions] 写入 system prompt。
 *
 * @param agentId agent 身份标识，典型取值为会话唯一标识（session.uniqueKey），唯一性由调用方保证。
 * @param memory 会话记忆，本次 spawn 产出的 boss 独占
 * @param scope 运行时上下文，只含可复用的运行时组件
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(agentId: String, memory: Memory, scope: AgentScope): BossAgent {
    // 显式契约：boss 角色由框架定义，外部 persona 无落点，配置即违约 fail-fast。
    require(persona.isNullOrBlank()) {
        "BossAgent's role is owned by the framework — persona must be null or blank; use instructions to customize the boss."
    }
    val resolveInfo = scope.resolver().resolve(this@spawnBoss)
    return bossAgent {
        id(agentId)
        persona(AgentPersona("").apply { instructions?.let { instructions(it) } })
        llmProvider(resolveInfo.llmProvider)
        memory(memory, maxMemoryRounds)
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
