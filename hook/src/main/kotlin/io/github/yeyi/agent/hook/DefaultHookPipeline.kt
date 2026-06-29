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
 * 调度语义：
 * - [AgentHookEvent.BeforeToolCall]：首个返回 [HookResult.Halt] 的 hook 短路
 * - [AgentHookEvent.AfterToolCall]：链式改写，后续 hook 收到前一个的输出
 * - 其他事件：fan-out，所有匹配的 hook 按 priority 顺序被调用
 *
 * 异常隔离：任意内部 hook 抛 [kotlinx.coroutines.CancellationException] 会被原样抛出；
 * 其他 [Throwable] 会被吞掉并记录，继续执行。
 */
internal class DefaultHookPipeline(initialHooks: List<Hook> = emptyList(), logging: Boolean = false) :
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

    override suspend fun run(event: HookEvent, context: HookContext): HookResult {
        val eventClass = event::class
        val matchingHooks = hooks.filter { hook ->
            hook.events?.any { subscribed -> subscribed.java.isAssignableFrom(eventClass.java) } ?: true
        }
        if (matchingHooks.isEmpty()) {
            return HookResult.Continue
        }

        return when (event) {
            is AgentHookEvent.BeforeToolCall -> runBeforeToolCall(matchingHooks, event, context)
            is AgentHookEvent.AfterToolCall -> runAfterToolCall(matchingHooks, event, context)
            else -> runFanOut(matchingHooks, event, context)
        }
    }

    private suspend fun runFanOut(
        hooks: List<Hook>,
        event: HookEvent,
        context: HookContext
    ): HookResult {
        var lastResult: HookResult = HookResult.Continue
        for (hook in hooks) {
            lastResult = invokeHook(hook, event, context)
        }
        return lastResult
    }

    private suspend fun runBeforeToolCall(
        hooks: List<Hook>,
        event: AgentHookEvent.BeforeToolCall,
        context: HookContext
    ): HookResult {
        for (hook in hooks) {
            val result = invokeHook(hook, event, context)
            if (result is HookResult.Halt) {
                return result
            }
        }
        return HookResult.Continue
    }

    private suspend fun runAfterToolCall(
        hooks: List<Hook>,
        event: AgentHookEvent.AfterToolCall,
        context: HookContext
    ): HookResult {
        var currentEvent = event
        for (hook in hooks) {
            when (val result = invokeHook(hook, currentEvent, context)) {
                is HookResult.Modify -> {
                    currentEvent = AgentHookEvent.AfterToolCall(
                        toolCall = currentEvent.toolCall,
                        result = result.newResult as ToolExecutionResult,
                        durationMs = currentEvent.durationMs
                    )
                }

                is HookResult.Halt -> {
                    return result
                }

                is HookResult.Continue -> {
                    // 继续
                }
            }
        }
        return HookResult.Modify(currentEvent.result)
    }

    private suspend fun invokeHook(
        hook: Hook,
        event: HookEvent,
        context: HookContext
    ): HookResult {
        return try {
            hook.execute(event, context)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            log.warn("${hook.name} hook exception", t)
            HookResult.Continue
        }
    }

    override fun getHooks(): List<Hook> = hooks.toList()

    override fun getHooks(eventClass: KClass<out HookEvent>): List<Hook> =
        hooks.filter { hook ->
            hook.events?.any { subscribed -> subscribed.java.isAssignableFrom(eventClass.java) } ?: true
        }

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
            is HookResult.Halt -> ToolExecutionResult.error(result.syntheticResult)
            else -> null
        }
    }

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long
    ): ToolExecutionResult {
        return when (val pipelineResult = run(AgentHookEvent.AfterToolCall(call, result, durationMs), HookContext(context))) {
            is HookResult.Modify -> pipelineResult.newResult as ToolExecutionResult
            else -> result
        }
    }

    override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
        run(AgentHookEvent.RunCompleted(result), HookContext(context))
    }

    override suspend fun onError(context: AgentContext, cause: AgentException) {
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
