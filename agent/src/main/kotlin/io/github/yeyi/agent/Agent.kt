package io.github.yeyi.agent

import kotlinx.coroutines.flow.Flow

/**
 * Agent 执行接口，提供两种调用路径。
 *
 * [run] 与 [runStream] 均通过 [AgentEvent] Flow 向调用方推送中间状态，
 * 最终以 [AgentEvent.Final] 或 [AgentEvent.Failed] 终止。
 *
 * 调用方可通过 [kotlinx.coroutines.flow.first] 或 [kotlinx.coroutines.flow.last] 获取最终结果，
 * 也可全程订阅事件流实现实时 UI 反馈。
 */
public interface Agent {
    /**
     * 批式（非流式）执行路径。
     *
     * 内部使用 [io.github.yeyi.agent.memory.Memory] 维护对话历史，
     * 调用 [io.github.yeyi.agent.llm.LlmProvider.chat] 单次 RTT。
     *
     * 适用场景：响应速度优先、无需流式输出。
     */
    public fun run(input: String): Flow<AgentEvent>

    /**
     * 流式执行路径。
     *
     * 内部使用 [io.github.yeyi.agent.memory.Memory] 维护对话历史，
     * 调用 [io.github.yeyi.agent.llm.LlmProvider.chatStream] 推送 [AgentEvent.TextDelta] 增量文本。
     *
     * 适用场景：需要实时展示 LLM 输出文字、工具调用进度等。
     */
    public fun runStream(input: String): Flow<AgentEvent>
}
