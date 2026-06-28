package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import kotlin.reflect.KClass

/**
 * Agent 生命周期所有事件的集合。
 * 供 LoggingHook 等订阅全部事件的 hook 使用。
 */
public object AgentEvents {
    public val ALL: Set<KClass<out Event>> = setOf(
        BeforeMemoryCompress::class,
        AfterMemoryCompress::class,
        BeforeLlmCall::class,
        AfterLlmResponse::class,
        BeforeToolCall::class,
        AfterToolCall::class,
        OnRunFinished::class,
        OnError::class
    )
}

// Agent 生命周期事件

public data class BeforeMemoryCompress(
    val summaries: List<Summary>
) : Event

public data class AfterMemoryCompress(
    val summaries: List<Summary>
) : Event

public object BeforeLlmCall : Event

public data class AfterLlmResponse(
    val response: ChatResponse
) : Event

public data class BeforeToolCall(
    val toolCall: ToolCall
) : Event

public data class AfterToolCall(
    val toolCall: ToolCall,
    val result: ToolExecutionResult,
    val durationMs: Long
) : Event

public data class OnRunFinished(
    val result: AgentResult
) : Event

public data class OnError(
    val error: AgentException
) : Event