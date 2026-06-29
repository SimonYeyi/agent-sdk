package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall

/**
 * Agent 生命周期事件。
 */
public sealed interface AgentHookEvent : HookEvent {
    public data class BeforeMemoryCompress(
        val summaries: List<Summary>
    ) : AgentHookEvent

    public data class AfterMemoryCompress(
        val summaries: List<Summary>
    ) : AgentHookEvent

    public object BeforeLlmCall : AgentHookEvent

    public data class AfterLlmResponse(
        val response: ChatResponse
    ) : AgentHookEvent

    public data class BeforeToolCall(
        val toolCall: ToolCall
    ) : AgentHookEvent

    public data class AfterToolCall(
        val toolCall: ToolCall,
        val result: ToolExecutionResult,
        val durationMs: Long
    ) : AgentHookEvent

    public data class RunCompleted(
        val result: AgentResult
    ) : AgentHookEvent

    public data class RunFailed(
        val error: AgentException
    ) : AgentHookEvent
}
