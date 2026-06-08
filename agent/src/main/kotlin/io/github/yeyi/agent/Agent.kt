package io.github.yeyi.agent

import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.flow.Flow

public interface Agent {
    public val config: AgentConfig

    /** 单轮:不维护历史(内部使用临时 InMemoryMemory,run 完即丢弃) */
    public fun run(input: String): Flow<AgentEvent>

    /** 多轮批式:传入 memory 保留历史,使用 chat() 单次 RTT */
    public fun run(input: String, memory: Memory): Flow<AgentEvent>

    /** 多轮流式:传入 memory 保留历史,使用 chatStream() 推送 TextDelta */
    public fun runStream(input: String, memory: Memory): Flow<AgentEvent>
}
