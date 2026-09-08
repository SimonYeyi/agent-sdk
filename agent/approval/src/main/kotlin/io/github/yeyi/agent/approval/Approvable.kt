package io.github.yeyi.agent.approval

import kotlinx.serialization.json.JsonElement

/**
 * 工具实现此接口表示其执行前需要经过审批决策。
 *
 * 默认行为（[requiresApproval] 返回 true）保持向后兼容：未 override 时，每次调用都需
 * 进入审批流程。
 *
 * 实现者可基于 'arguments' 决策：
 * ```kotlin
 * class BashTool : Tool, Approvable {
 *     override fun requiresApproval(arguments: JsonElement): Boolean {
 *         val cmd = (arguments as? JsonObject)?.get("cmd")?.jsonPrimitive?.content
 *         return cmd?.startsWith("rm") == true || cmd?.startsWith("sudo") == true
 *     }
 * }
 * ```
 *
 * 委托工具（实现 [io.github.yeyi.agent.tool.DelegatingTool]）无需实现此接口 ——
 * [io.github.yeyi.agent.approval.ApprovalHook] 会递归穿透委托链，基于底层目标
 * Tool 的 [Approvable] 策略做审批决策，内部成员工具的审批需求自动生效。
 */
public interface Approvable {
    /**
     * 当前调用是否需要进入审批流程。
     *
     * @param arguments 工具调用的实际参数（JSON 结构）
     * @return true 表示需要审批；false 表示直接放行
     */
    public fun requiresApproval(arguments: JsonElement): Boolean = true
}
