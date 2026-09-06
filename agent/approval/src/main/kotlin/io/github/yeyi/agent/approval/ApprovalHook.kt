package io.github.yeyi.agent.approval

import io.github.yeyi.agent.hook.AgentHookEvent
import io.github.yeyi.agent.hook.Hook
import io.github.yeyi.agent.hook.HookContext
import io.github.yeyi.agent.hook.HookEvent
import io.github.yeyi.agent.hook.HookResult
import kotlin.reflect.KClass

/**
 * 审批 Hook，拦截需要审批的工具执行。
 *
 * 用法：
 * ```kotlin
 * val agent = agent {
 *     llmProvider(...)
 *     hook(HookPipeline(listOf(ApprovalHook(myApprover))))
 *     tool(DangerousTool()) // 实现 ApprovalRequired
 * }
 * ```
 */
public class ApprovalHook(
    private val approver: Approver,
) : Hook {
    override val events: Set<KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeToolCall::class)

    override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
        val toolCall = (event as AgentHookEvent.BeforeToolCall).toolCall
        val tool = context.agentContext?.tools?.find { it.name == toolCall.name }

        if (tool !is ApprovalRequired) {
            return HookResult.Continue
        }

        val decision = approver.requireApproval(ApprovalContext(toolCall.name, toolCall.arguments))
        return when (decision) {
            is ApprovalDecision.Approved -> HookResult.Continue
            is ApprovalDecision.Denied -> HookResult.Refuse(decision.reason ?: "工具审批被拒绝")
        }
    }
}
