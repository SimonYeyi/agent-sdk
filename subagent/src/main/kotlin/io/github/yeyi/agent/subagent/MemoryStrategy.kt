package io.github.yeyi.agent.subagent

/**
 * Subagent 的内存策略。
 */
public enum class MemoryStrategy {
    /** 每次调用独立 memory（默认；隔离上下文） */
    Isolated,
    /** 同一 subagent 实例跨调用共享 memory（保留上下文供后续轮次） */
    Shared,
}
