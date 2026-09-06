package io.github.yeyi.agent.approval

/**
 * 审批决策。
 */
public sealed class ApprovalDecision {
    /** 用户批准，可以继续执行。 */
    public object Approved : ApprovalDecision()

    /** 用户拒绝执行。
     * @param reason 拒绝原因，可选。
     */
    public data class Denied(public val reason: String? = null) : ApprovalDecision()
}
