package io.github.yeyi.agent.demo.agent.runtime.approval

import io.github.yeyi.agent.approval.ApprovalContext
import io.github.yeyi.agent.approval.ApprovalDecision
import io.github.yeyi.agent.approval.Approver
import io.github.yeyi.agent.demo.agent.log.log

/**
 * demo 的审批者：共享的**审批策略**，所有 agent 共用一份。
 *
 * 审批器只做决策、不做投递——「批不批这个调用」不依赖 agent 身份，
 * 请求来自谁由 [ApprovalContext.agentId] 带上（日志与按 agent 区分的策略都能读）。
 *
 * demo 暂无交互式审批界面（审批框要阻塞轮次等答复，与「卡片动作回流触发新一轮」的
 * 问询卡是两条链路），故此处先自动批准。将来接审批 UI 时，要 per-agent 的不是策略
 * 而是**出口**（审批请求投到哪个会话）——那时审批器才需要变成 agent 私有实例，
 * 形状与产卡工具一致（都持有本 agent 的通道）。
 */
internal class AgentApprover : Approver {

    override suspend fun approve(context: ApprovalContext): ApprovalDecision {
        log.info("[${context.agentId}] approve tool=${context.toolName} args=${context.toolArguments}")
        return ApprovalDecision.Approved
    }
}
