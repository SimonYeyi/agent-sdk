package io.github.yeyi.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/**
 * Agent 执行接口——本质契约。
 *
 * [run] 通过 [AgentEvent] Flow 向调用方推送中间状态，终局**三选一**：
 * - [AgentEvent.Final] —— 正常终结，携带最终结果；
 * - [AgentEvent.Failed] —— 运行失败，携带原始异常；
 * - [kotlinx.coroutines.CancellationException] —— 回合被取消（如：[Agent.Cancelable]）
 *
 * 三者互斥且必有其一：一次收集不会以"零事件 + 正常结束"收尾。
 *
 * 调用方可通过 [kotlinx.coroutines.flow.first] 或 [kotlinx.coroutines.flow.last] 获取最终结果，
 * 也可全程订阅事件流实现实时 UI 反馈。
 *
 * 流式（[Streamable.runStream]）与在途指令注入（[Steerable.steer]）均为可选能力，分别由嵌套的
 * [Streamable] 与 [Steerable] 独立暴露——不实现该能力的 Agent 不承担对应契约。
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
     *
     * **轮次语义**：返回的 [Flow] 代表一个轮次，**只允许收集一次**——重复收集是调用方错误。
     *
     * 实现 [Agent.Cancelable] 的 Agent 在本方法
     * 调用时即占用活跃轮次：若最终不收集该流，必须调用 [Agent.Cancelable.cancel] 释放占用，
     * 否则 agent 停留在"已有活跃轮次"，后续 [run] 抛 [IllegalStateException]。
     */
    public fun run(query: AgentQuery): Flow<AgentEvent>

    /**
     * 流式执行能力。
     *
     * 仅实现本接口的 Agent 才具备流式路径——调用方应通过 `agent is Agent.Streamable`
     * 探测能力，而非无条件调用 [runStream]。
     *
     * 内部使用 [io.github.yeyi.agent.memory.Memory] 维护对话历史，
     * 调用 [io.github.yeyi.agent.llm.LlmProvider.chatStream] 推送 [AgentEvent.TextDelta] 增量文本。
     * 入参 [AgentQuery] 承载文本 + 多模态块。
     *
     * 适用场景：需要实时展示 LLM 输出文字、工具调用进度等。
     *
     * 轮次语义与 [run] 一致（**只允许收集一次**、不收集须 [Agent.Cancelable.cancel] 释放占用）——
     * 两者是同一个"轮次"的两种执行方式，不能对同一轮既 [run] 又 [runStream]。
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
     * - `true`：指令已被当前轮次接受，将在当前 run 的后续轮次中生效
     * - `false`：无活跃 run，指令未送达；调用方应改为调用 [Agent.run] 启动新 run
     *
     * **`true` 的边界**：它承诺"已被接受"，不承诺"必被消费"—— 若该轮在消费之前
     * 被打断或异常终止，尚未消费的在途指令随该轮一并作废（轮次都没了，"下一轮"无从谈起；
     * 在途指令也不跨轮）。终局不会带走未消费的指令：[Agent.run] 正常收尾时，
     * 若信箱非空则抢占 Final、强制再跑一轮把指令送进模型。
     *
     * **`false` 的保证**：返回 `false` 时必然没有活跃轮次，调用方紧接着调 [Agent.run] 必定起得来。
     *
     * 典型用法：
     * ```
     * if (!agent.steer(query)) {
     *     agent.run(query).collect { event -> ... }
     * }
     * ```
     *
     * 打断之后不要再 steer —— 那一轮已死，指令会被丢弃；应当另起一轮
     * （先 [Agent.Cancelable.cancel] 并等待收尾句柄完成，则随后的 steer 会如实返回 `false`，
     * 兜底的 [Agent.run] 便能正常触发）。
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
     * 回合中断能力。
     *
     * 仅实现本接口的 Agent 才可被 [cancel] 打断当前回合——调用方应通过
     * `agent is Agent.Cancelable` 探测能力，而非无条件调用 [cancel]。
     *
     * [cancel] 只作用于当前回合，不改变 Agent 自身状态：打断后 Agent 仍可再次 [Agent.run]。
     * 终止协议与 [Shutdownable] 一致：进行中的 [Agent.run] flow 收集以 [CancellationException] 结束，
     * 调用方沿用"collect 外围 try/catch 取消异常"的既有用法即可。
     *
     * **打断后想立刻重开，要等 [cancel] 返回的句柄**：打断只投递取消，
     * 旧轮的收尾（槽位归还、信箱关闭）在收集方侧进行 —— [cancel] 返回时往往还没收完，
     * 此刻开新轮会撞上 [IllegalStateException]（"Concurrent run not supported"）。
     * [kotlinx.coroutines.Job.join] 该句柄后再开新轮则必定成功。
     *
     * 与 [Shutdownable] 的分工：[cancel] 是可恢复的中断（回合结束，Agent 继续可用）；
     * [Shutdownable.shutdown] 是终态操作（Agent 作废，之后调用 [Agent.run] 抛 [IllegalStateException]）。
     *
     * **未收集轮次的释放义务**：实现本能力的 Agent 通常在 [Agent.run] 调用时即占用活跃轮次 ——
     * 若返回的流最终不被收集，必须调用 [cancel] 作废该轮并释放占用，
     * 否则 agent 停留在"已有活跃轮次"，后续 [Agent.run] 抛 [IllegalStateException]。
     *
     * **已关闭的 Agent**：[Shutdownable.shutdown] 之后调用 [cancel] 的行为由实现定义 ——
     * 终态语义下已关闭的 Agent 不再接受任何操作（实现可抛 [IllegalStateException]）。
     *
     * 适用场景：用户主动打断、任务重规划、Agent 超时兜底。
     */
    public interface Cancelable {
        /**
         * 打断当前正在执行的回合。
         *
         * 无活跃回合时为 no-op（幂等）；
         * 若回合已通过[Agent.run] 或 [Streamable.runStream] 创建，则 [Flow.collect] 以 [CancellationException] 结束
         * - 若在[cancel]之后被收集，将以 [CancellationException] 直接结束；
         * - 若回合已在执行，则打断其执行体，以 [CancellationException] 结束。
         *
         * @return 该轮收尾的句柄 —— 完成即代表该轮已彻底退场（槽位已还、信箱已关），
         *   此后调用方可安全地再次 [Agent.run]；无活跃回合时返回 null。
         *
         *   **等或不等，由调用方决定**：不等待时，[cancel] 只投递打断，旧轮仍在收尾，
         *   此刻开新轮仍会撞上 [IllegalStateException]（"Concurrent run not supported"）；
         *   [kotlinx.coroutines.Job.join] 该句柄再开新轮则必定成功。
         *
         *   纪律：严禁在收集回调内 join 该句柄 —— 收尾信号由收集方收尾时完成，
         *   在收集回调里等它就是等自己（自等自，死锁）。
         *
         *   纪律：**不要 cancel 该句柄**。它是一个只读的"退场完成"信号，
         *   实现上是一个 [kotlinx.coroutines.CompletableDeferred]，
         *   而 [Job.cancel] 是公开方法 —— 一旦被取消，[kotlinx.coroutines.Job.join]
         *   会立刻以 [CancellationException] 结束，而此时槽位可能还没还、
         *   旧轮可能还在收尾，"join 之后开新轮必定成功"的承诺随之失效。
         */
        public fun cancel(): Job?
    }

    /**
     * 整体关闭能力。
     *
     * 仅实现本接口的 Agent 才可被 [shutdown] 永久关闭——调用方应通过 `agent is Agent.Shutdownable`
     * 探测能力，而非无条件调用 [shutdown]。
     *
     * [shutdown] 是终态操作：停止 Agent 的后台活动、终止进行中的 [Agent.run] flow 并回收其持有的
     * 外部资源（如 MCP 子进程）。终止协议统一：进行中的 [Agent.run] flow 收集以 [CancellationException]
     * 结束（尚未被收集的 flow 在收集时同样以 [CancellationException] 结束，不静默零事件）；
     * 之后调用 [Agent.run] 抛出 [IllegalStateException]（Agent 已关闭）；如需继续使用必须重新创建实例。
     * 幂等：可重复调用，第二次无副作用。
     *
     * 关闭时需调用 [Cancelable.cancel] 确保轮次同时终止；
     * 终态语义下已关闭的 Agent 不再接受任何操作（实现可抛 [IllegalStateException]）。
     *
     * 适用场景：会话/应用结束时的资源回收，以及任何带后台协程或外部资源的 Agent 的整体终止。
     */
    public interface Shutdownable {
        /** 永久关闭 Agent 并回收资源。幂等，可重复调用。 */
        public fun shutdown()
    }
}
