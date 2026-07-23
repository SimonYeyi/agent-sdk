package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * 测试用 [AgentHook] 骨架,提供全部 6 个无参方法的空实现。
 *
 * 用于让测试只覆盖关心的方法,其余委托给本类的 no-op,
 * 避免在每个 `object : AgentHook` 处重复 6 个空 override。
 */
internal open class EmptyAgentHook : AgentHook {
    override suspend fun beforeMemoryCompress(
        context: AgentContext,
        summaries: List<Summary>,
    ) {
    }

    override suspend fun afterMemoryCompress(
        context: AgentContext,
        summaries: List<Summary>,
    ) {
    }

    override suspend fun beforeLlmCall(context: AgentContext) {
    }

    override suspend fun afterLlmResponse(
        context: AgentContext,
        response: ChatResponse,
    ) {
    }

    override suspend fun beforeToolCall(
        context: AgentContext,
        call: ToolCall
    ): ToolExecutionResult? = null

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        synthetic: Boolean,
        durationMs: Long
    ): ToolExecutionResult = result

    override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) {
    }

    override suspend fun onRunFailed(context: AgentContext, cause: Throwable) {
    }
}
