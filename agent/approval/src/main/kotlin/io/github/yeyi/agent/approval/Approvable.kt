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
 * 委托工具（如单 tool name 路由到多个内部能力的场景）也可 override 此方法，
 * 在内部展开目标并基于目标策略返回决策，approval 模块本身无需感知委托概念。
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
