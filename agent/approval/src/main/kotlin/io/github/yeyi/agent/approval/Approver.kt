package io.github.yeyi.agent.approval

/**
 * 审批者接口。
 *
 * 实现此接口来处理工具执行前的用户审批，
 * 例如弹出确认对话框、发送飞书审批流程等。
 */
public interface Approver {
    /**
     * 用户审批并返回审批决策。
     *
     * @param context 审批上下文，包含工具名和调用参数
     * @return 审批决策
     */
    public suspend fun approve(context: ApprovalContext): ApprovalDecision
}
