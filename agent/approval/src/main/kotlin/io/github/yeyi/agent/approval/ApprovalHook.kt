package io.github.yeyi.agent.approval

import io.github.yeyi.agent.hook.AgentHookEvent
import io.github.yeyi.agent.hook.Hook
import io.github.yeyi.agent.hook.HookContext
import io.github.yeyi.agent.hook.HookEvent
import io.github.yeyi.agent.hook.HookResult
import io.github.yeyi.agent.tool.DelegateTarget
import io.github.yeyi.agent.tool.DelegatingTool
import io.github.yeyi.agent.tool.Tool
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.KClass

/**
 * 审批 Hook，拦截需要审批的工具执行。
 *
 * 支持委托工具穿透：当工具是 [DelegatingTool] 时，递归解析到底层目标 Tool，
 * 基于底层 Tool 的 [Approvable] 策略与参数做审批决策。这样委托工具自身无需
 * 实现 [Approvable]，内部成员工具的审批需求自动生效。
 *
 * 无法解析的委托（参数缺失、目标不存在）按放行处理，避免阻塞正常调用。
 *
 * 用法：
 * ```kotlin
 * val agent = agent {
 *     llmProvider(...)
 *     hook(HookPipeline(listOf(ApprovalHook(myApprover))))
 *     tool(DangerousTool()) // 实现 Approvable
 * }
 * ```
 */
public class ApprovalHook(
    private val approver: Approver,
) : Hook {
    override val events: Set<KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeToolCall::class)

    override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
        val toolCall = (event as AgentHookEvent.BeforeToolCall).toolCall
        val tool = context.agentContext?.tools?.find { it.name == toolCall.name }!!

        val target = resolveTarget(tool, toolCall.arguments)
        val targetTool = target.tool

        // 非 Approvable 或 requiresApproval=false 也放行
        if (targetTool !is Approvable || !targetTool.requiresApproval(target.arguments)) {
            return HookResult.Continue
        }

        return when (val decision =
            approver.approval(ApprovalContext(targetTool.name, target.arguments))) {
            is ApprovalDecision.Approved -> HookResult.Continue
            is ApprovalDecision.Denied -> HookResult.Refuse(decision.reason ?: "工具审批被拒绝")
        }
    }

    /**
     * 递归穿透 [DelegatingTool] 委托链，定位到底层目标 Tool 及其参数。
     *
     * 委托工具 [DelegatingTool.resolveTarget] 解析失败时会抛异常，由调用方 try-catch 兜底。
     */
    private fun resolveTarget(tool: Tool, arguments: JsonElement): DelegateTarget {
        if (tool !is DelegatingTool) return DelegateTarget(tool, arguments)
        val next = tool.resolveTarget(arguments)
        return resolveTarget(next.tool, next.arguments)
    }
}
