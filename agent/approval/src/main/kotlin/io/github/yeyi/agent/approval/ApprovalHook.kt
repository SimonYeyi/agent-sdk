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
 * [DelegatingTool.resolveTarget] 解析失败时抛出 [IllegalArgumentException]，
 * 由 hook 流水线异常隔离捕获，调用按放行处理（等同于默认 HookResult.Continue 语义）。
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
        val tool = context.agentContext?.tools?.find { it.name == toolCall.name }
            ?: return HookResult.Continue

        val target = resolveTarget(tool, toolCall.arguments)
        val targetTool = target.tool

        if (targetTool !is Approvable || !targetTool.requiresApproval(target.arguments)) {
            return HookResult.Continue
        }

        return when (val decision = approver.approval(ApprovalContext(targetTool.name, target.arguments))) {
            is ApprovalDecision.Approved -> HookResult.Continue
            is ApprovalDecision.Denied -> HookResult.Refuse(decision.reason ?: "工具审批被拒绝")
        }
    }

    /**
     * 递归穿透 [DelegatingTool] 委托链，定位到底层目标 Tool 及其参数。
     * 解析失败时 [DelegatingTool.resolveTarget] 会抛 [IllegalArgumentException]，
     * 由 hook 流水线异常隔离统一兜底（按 Continue 放行，记 WARN 日志）。
     */
    private tailrec fun resolveTarget(tool: Tool, arguments: JsonElement): DelegateTarget {
        if (tool !is DelegatingTool) return DelegateTarget(tool, arguments)
        val next = tool.resolveTarget(arguments)
        return resolveTarget(next.tool, next.arguments)
    }
}
