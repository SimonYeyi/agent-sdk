package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlin.reflect.KClass

/**
 * [HookPipeline] 的默认实现。
 *
 * [io.github.yeyi.agent.ReActAgent] 只接受**单个** hook 入口；实际项目里通常需要挂载多个（如日志 + 指标 + 审计），
 * 这种场景下用 [DefaultHookPipeline] 把它们串起来再传给 ReActAgent。
 *
 * [DefaultHookPipeline] 实现 [HookPipeline]（即 [AgentHook]），可直接传给 ReActAgent。
 * 内部按 priority 排序调用 sub-hooks。
 *
 * 调度语义（所有事件通用）：
 * - [HookResult.Halt]：立即返回，中断后续 hook
 * - [HookResult.Modify]：记录修改，通过 [HookEvent.copyWith] 更新事件状态供下一个 hook 消费
 * - [HookResult.Continue]：继续下一个 hook
 * - 未匹配到任何 hook 的事件返回 [HookResult.Continue]
 *
 * 异常隔离：任意内部 hook 抛 [kotlinx.coroutines.CancellationException] 会被原样抛出；
 * 其他 [Throwable] 会被吞掉并记录，继续执行。
 */
internal class DefaultHookPipeline(
    initialHooks: List<Hook> = emptyList(),
    logging: Boolean = false
) :
    HookPipeline {
    private val hooks = mutableListOf<Hook>()

    init {
        if (logging) hooks.add(LoggingHook())
        hooks.addAll(initialHooks)
        sortHooks()
    }

    private fun sortHooks() {
        hooks.sortByDescending { it.priority }
    }

    override fun register(hook: Hook) {
        hooks.add(hook)
        sortHooks()
    }

    override fun unregister(hookName: String) {
        hooks.removeAll { it.name == hookName }
    }

    override fun unregister(hookClass: KClass<out Hook>) {
        hooks.removeAll { it::class == hookClass }
    }

    override fun getHooks(): List<Hook> = hooks.toList()

    override fun getHooks(eventClass: KClass<out HookEvent>): List<Hook> =
        hooks.filter { matches(it, eventClass) }

    override suspend fun run(event: HookEvent, context: HookContext): HookResult {
        val matchingHooks = hooks.filter { matches(it, event::class) }
        if (matchingHooks.isEmpty()) return HookResult.Continue
        return runEvents(matchingHooks, event, context)
    }

    private suspend fun runEvents(
        hooks: List<Hook>,
        event: HookEvent,
        context: HookContext
    ): HookResult {
        var currentEvent = event
        var lastModify: HookResult.Modify? = null
        for (hook in hooks) {
            val result: HookResult = try {
                val raw = hook.execute(currentEvent, context)
                raw.let { it as? HookResult.Modify }
                    ?.also { currentEvent = currentEvent.copyWith(it.newResult) }
                    ?.also { lastModify = it } ?: raw
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                log.warn("${hook.name} hook exception", t)
                HookResult.Continue
            }
            if (result is HookResult.Halt) return result
        }
        return lastModify ?: HookResult.Continue
    }

    private fun matches(hook: Hook, eventClass: KClass<out HookEvent>): Boolean =
        hook.events?.any { it.java.isAssignableFrom(eventClass.java) } ?: true

    // ==================== AgentHook 实现（委托给 run） ====================

    override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) {
        run(AgentHookEvent.BeforeMemoryCompress(summaries), HookContext(context))
    }

    override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) {
        run(AgentHookEvent.AfterMemoryCompress(summaries), HookContext(context))
    }

    override suspend fun beforeLlmCall(context: AgentContext) {
        run(AgentHookEvent.BeforeLlmCall, HookContext(context))
    }

    override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
        run(AgentHookEvent.AfterLlmResponse(response), HookContext(context))
    }

    override suspend fun beforeToolCall(
        context: AgentContext,
        call: ToolCall
    ): ToolExecutionResult? {
        return when (val result = run(AgentHookEvent.BeforeToolCall(call), HookContext(context))) {
            is HookResult.Halt -> ToolExecutionResult.error(result.reason)
            else -> null
        }
    }

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        synthetic: Boolean,
        durationMs: Long
    ): ToolExecutionResult {
        val event = AgentHookEvent.AfterToolCall(call, result, synthetic, durationMs)
        return when (val pipelineResult = run(event, HookContext(context))) {
            is HookResult.Modify -> pipelineResult.newResult as ToolExecutionResult
            else -> result
        }
    }

    override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) {
        run(AgentHookEvent.RunCompleted(result), HookContext(context))
    }

    override suspend fun onRunFailed(context: AgentContext, cause: AgentException) {
        run(AgentHookEvent.RunFailed(cause), HookContext(context))
    }
}

/**
 * 创建 [HookPipeline] 实例的工厂函数。
 */
public fun HookPipeline(
    initialHooks: List<Hook> = emptyList(),
    logging: Boolean = false
): HookPipeline = DefaultHookPipeline(initialHooks, logging)
