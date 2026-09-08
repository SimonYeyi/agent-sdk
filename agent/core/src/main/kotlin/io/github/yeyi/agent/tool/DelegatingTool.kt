package io.github.yeyi.agent.tool

import kotlinx.serialization.json.JsonElement

/**
 * 委托工具接口 — 将自身调用转发给另一个 [Tool] 的工具实现此接口。
 *
 * 实现者需同时：
 * - 在 [resolveTarget] 中从原始参数解析路由字段，返回目标 [Tool] 及其实际参数
 * - 在 [Tool.execute] 中复用 [resolveTarget] 获取目标并转发执行，避免路由解析逻辑重复
 *
 * [resolveTarget] 返回的目标 [Tool] 自身也可能是 [DelegatingTool]（多层委托），
 * 调用方应递归穿透直至非委托 [Tool]，并使用每层返回的 [DelegateTarget.arguments]
 * 作为下一层的输入。
 *
 * 解析失败（参数缺失、目标不存在等）应抛异常，而非返回 null —— 委托工具的
 * [resolveTarget] 与 [Tool.execute] 共享同一套解析逻辑，解析失败即调用失败。
 *
 * @see DelegateTarget
 */
public interface DelegatingTool {

    /**
     * 解析本次委托调用的目标 [Tool] 及其参数。
     *
     * @param arguments 委托工具收到的原始参数（JSON 结构），实现者需从中解析路由字段
     * @return 目标 [Tool] 及其参数
     * @throws IllegalArgumentException 参数缺失或目标不存在时抛出
     */
    public fun resolveTarget(arguments: JsonElement): DelegateTarget
}

/**
 * 委托调用的解析结果：目标 [Tool] 及其参数。
 *
 * @param tool 目标 [Tool]；若自身也是 [DelegatingTool]，调用方应继续穿透
 * @param arguments 传给目标 [Tool] 的参数（从委托参数中剥离路由字段后的部分）
 */
public data class DelegateTarget(
    public val tool: Tool,
    public val arguments: JsonElement,
)
