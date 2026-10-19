package io.github.yeyi.agent

import kotlinx.coroutines.flow.Flow

/**
 * Agent 执行接口——本质契约。
 *
 * [run] 通过 [AgentEvent] Flow 向调用方推送中间状态，最终以
 * [AgentEvent.Final] 或 [AgentEvent.Failed] 终止。
 *
 * 调用方可通过 [kotlinx.coroutines.flow.first] 或 [kotlinx.coroutines.flow.last] 获取最终结果，
 * 也可全程订阅事件流实现实时 UI 反馈。
 *
 * 流式（[Streamable.runStream]）与在途指令注入（[Steerable.steer]）均为可选能力，分别由 [Streamable]
 * 与 [Steerable] 独立暴露——不实现该能力的 Agent 不承担对应契约。
 */
public interface Agent {
    /**
     * 批式（非流式）执行路径。
     *
     * 内部使用 [io.github.yeyi.agent.memory.Memory] 维护对话历史，
     * 调用 [io.github.yeyi.agent.llm.LlmProvider.chat] 单次 RTT。
     * 入参 [AgentQuery] 承载文本 + 多模态块。
     *
     * 适用场景：响应速度优先、无需流式输出。
     */
    public fun run(query: AgentQuery): Flow<AgentEvent>
}

/**
 * 流式执行能力。
 *
 * 仅实现本接口的 Agent 才具备流式路径——调用方应通过 `agent is Streamable`
 * 探测能力，而非无条件调用 [runStream]。
 *
 * 内部使用 [io.github.yeyi.agent.memory.Memory] 维护对话历史，
 * 调用 [io.github.yeyi.agent.llm.LlmProvider.chatStream] 推送 [AgentEvent.TextDelta] 增量文本。
 * 入参 [AgentQuery] 承载文本 + 多模态块。
 *
 * 适用场景：需要实时展示 LLM 输出文字、工具调用进度等。
 */
public interface Streamable {
    public fun runStream(query: AgentQuery): Flow<AgentEvent>
}

/**
 * 在途指令注入能力。
 *
 * 当 Agent 正在执行 [Agent.run] 时，[steer] 将指令注入当前运行的轮次，
 * 在下一个检查点（迭代头 / Final 前）生效。调用方无需判断 Agent 是否活跃——
 * [steer] 返回值即为送达确认：
 * - `true`：指令已注入，将在当前 run 的后续轮次中生效
 * - `false`：无活跃 run，指令未送达；调用方应改为调用 [Agent.run] 启动新 run
 *
 * 典型用法：
 * ```
 * if (!agent.steer(query)) {
 *     agent.run(query).collect { event -> ... }
 * }
 * ```
 *
 * 注入不携带观察义务——steer 不返回 Flow，调用方若需观察当前 run 的事件，
 * 应在启动 run 时 collect 返回的 Flow。
 */
public interface Steerable {
    /**
     * 向正在执行的 run 注入在途指令。
     *
     * @param query 用户在途指令，支持文本 + 多模态
     * @return `true` 表示已注入当前活跃 run；`false` 表示无活跃 run，未送达
     */
    public fun steer(query: AgentQuery): Boolean
}

/**
 * 整体关闭能力。
 *
 * 仅实现本接口的 Agent 才可被 [shutdown] 永久关闭——调用方应通过 `agent is Shutdownable`
 * 探测能力，而非无条件调用 [shutdown]。
 *
 * [shutdown] 是终态操作：停止 Agent 的后台活动并回收其持有的外部资源（如 MCP 子进程），
 * 之后调用 [Agent.run] 抛出 [IllegalStateException]（Agent 已关闭）；如需继续使用必须重新创建实例。
 * 幂等：可重复调用，第二次无副作用。
 *
 * 适用场景：会话/应用结束时的资源回收；BossAgent 等带后台协程的 Agent 的整体终止。
 */
public interface Shutdownable {
    /** 永久关闭 Agent 并回收资源。幂等，可重复调用。 */
    public fun shutdown()
}
