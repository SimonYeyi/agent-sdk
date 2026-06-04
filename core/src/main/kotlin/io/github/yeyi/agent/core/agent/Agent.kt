package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.memory.Memory
import kotlinx.coroutines.flow.Flow

public interface Agent {
    public val config: AgentConfig

    /** 单轮:不维护历史(每次 run 新建 InMemoryMemory) */
    public suspend fun run(input: String): AgentResult

    /** 多轮:传入 memory 保留历史 */
    public suspend fun run(input: String, memory: Memory): AgentResult

    /** 流式:边生成边 yield 事件 */
    public fun runStream(input: String, memory: Memory): Flow<AgentEvent>
}
