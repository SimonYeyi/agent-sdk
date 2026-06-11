package io.github.yeyi.agent

import kotlinx.coroutines.flow.Flow

public interface Agent {
    /** 批式:使用 [io.github.yeyi.agent.memory.Memory] 维护历史,内部调用 [io.github.yeyi.agent.llm.LlmClient.chat] 单次 RTT */
    public fun run(input: String): Flow<AgentEvent>

    /** 流式:使用 [io.github.yeyi.agent.memory.Memory] 维护历史,内部调用 [io.github.yeyi.agent.llm.LlmClient.chatStream] 推送 TextDelta */
    public fun runStream(input: String): Flow<AgentEvent>
}
