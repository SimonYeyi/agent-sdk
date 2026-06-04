package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.tool.ToolExecutionResult

/**
 * Agent 生命周期回调。所有方法默认 no-op。
 *
 * 契约:
 * 1. Hook 抛异常不影响主流程,会被 SDK 吞掉并 log
 * 2. Hook 不应阻塞/sleep,可能影响 agent 延迟
 * 3. Hook 不能修改 config/memory(应只读使用)
 * 4. 调用顺序: beforeLlmCall → afterLlmResponse → (beforeToolCall → afterToolCall)* → onRunFinished
 */
public interface AgentHook {
    public suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {}
    public suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {}
    public suspend fun beforeToolCall(call: ToolCall) {}
    public suspend fun afterToolCall(call: ToolCall, result: ToolExecutionResult, durationMs: Long) {}
    public suspend fun onError(iteration: Int, cause: Throwable) {}
    public suspend fun onRunFinished(result: AgentResult) {}
}

public object NoOpAgentHook : AgentHook
