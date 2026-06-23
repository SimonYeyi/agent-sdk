package io.github.yeyi.agent

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.Memory

/**
 * Agent 运行时上下文，供 [AgentHook] 使用。
 *
 * @param persona 当前 persona
 * @param maxIterations 最大迭代次数
 * @param currentIteration 当前迭代序号（从 1 开始）
 * @param memory 只读 memory，hooks 应只调用 history()
 * @param llmProvider 当前 agent 使用的 LLM provider，供 hooks 与 delegate tool 访问
 * @param hook 触发本 context 的 hook 链入口（用于 subagent 等递归场景）
 * @param maxRounds memory 保留的最大 user 消息轮次
 * @param metadata 扩展数据，hooks 可自由写入供后续 hooks 使用
 */
public class AgentContext(
    public val persona: Persona,
    public val maxIterations: Int,
    public val currentIteration: Int,
    public val memory: Memory,
    public val llmProvider: LlmProvider,
    public val hook: AgentHook = NoOpAgentHook,
    public val maxRounds: Int,
    public val metadata: MutableMap<String, String> = mutableMapOf(),
) {
    override fun toString(): String = buildString {
        append("iter=$currentIteration/$maxIterations")
        if (metadata.isNotEmpty()) {
            append(" metadata=$metadata")
        }
    }
}
