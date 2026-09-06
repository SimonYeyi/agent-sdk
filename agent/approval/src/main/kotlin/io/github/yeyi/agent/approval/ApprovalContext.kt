package io.github.yeyi.agent.approval

import kotlinx.serialization.json.JsonElement

/**
 * 审批请求上下文。
 *
 * @param toolName 待审批的工具名称
 * @param toolArguments 工具调用参数
 */
public data class ApprovalContext(
    public val toolName: String,
    public val toolArguments: JsonElement,
)
