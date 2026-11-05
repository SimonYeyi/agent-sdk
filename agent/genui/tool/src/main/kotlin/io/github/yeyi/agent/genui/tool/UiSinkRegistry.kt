package io.github.yeyi.agent.genui.tool

import java.util.concurrent.ConcurrentHashMap

/**
 * 按 agent 身份标识（agentId）路由卡片流的注册表。
 *
 * 工具实例被大量 agent 共享（含 SpawnedAgent 孵化），sink 不能再绑死在工具构造期——
 * 路由键取 agent 自身的身份标识：会话/应用为每个 agent 创建 [UiSink] 并 [register]，
 * 工具执行时从 [io.github.yeyi.agent.AgentContext.agentId] 解析标识、[resolve] 出本 agent
 * 的 sink，多 agent 共享同一工具实例天然隔离。agent 不感知任何会话概念，只认自身 id。
 *
 * 生命周期由持有者掌控：sink 不再需要时 [unregister]（幂等，重复调用无害）。
 * 并发安全：注册/解析可能来自不同线程（工具执行线程与装配线程），内部使用 [ConcurrentHashMap]。
 */
public class UiSinkRegistry {

    private val sinks = ConcurrentHashMap<String, UiSink>()

    /** 登记 agent 身份标识 → sink。同名重复登记覆盖旧值。 */
    public fun register(agentId: String, sink: UiSink) {
        sinks[agentId] = sink
    }

    /** 注销标识；不存在时静默无操作（幂等）。 */
    public fun unregister(agentId: String) {
        sinks.remove(agentId)
    }

    /** 解析标识对应的 sink；未登记返回 null，产卡工具视为装配配置缺失，fail-fast 抛异常暴露。 */
    public fun resolve(agentId: String): UiSink? = sinks[agentId]
}
