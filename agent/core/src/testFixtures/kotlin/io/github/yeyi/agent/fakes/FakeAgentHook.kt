package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * 所有回调都是空实现的 [AgentHook]。
 *
 * 专供测试里的假 [io.github.yeyi.agent.AgentPluginContext] 占位 —— 它们只关心
 * registerTool / addInstruction，不关心 hook 行为。要观察某次回调是否触发，
 * 另写记录型实现，不要往这里加状态。
 */
public object FakeAgentHook : AgentHook {
    override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) = Unit

    override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) = Unit

    override suspend fun beforeLlmCall(context: AgentContext, request: ChatRequest): ChatRequest = request

    override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) = Unit

    override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? = null

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        synthetic: Boolean,
        durationMs: Long,
    ): ToolExecutionResult = result

    override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) = Unit

    override suspend fun onRunFailed(context: AgentContext, cause: Throwable) = Unit
}