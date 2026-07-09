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
    /** 内存压缩前触发，summaries 为即将合并的历史摘要列表。 */
    public data class BeforeMemoryCompress(
        val summaries: List<Summary>
    ) : AgentHookEvent

    /** 内存压缩完成后触发，summaries 为压缩后的新摘要。 */
    public data class AfterMemoryCompress(
        val summaries: List<Summary>
    ) : AgentHookEvent

    /** 每次 LLM 调用前触发。 */
    public object BeforeLlmCall : AgentHookEvent {
        override fun toString(): String = "BeforeLlmCall"
    }

    /** 每次 LLM 响应后触发，response 含 LLM 本次回复内容。 */
    public data class AfterLlmResponse(
        val response: ChatResponse
    ) : AgentHookEvent

    /** 每次工具调用前触发，返回非 null 可短路真实工具执行。 */
    public data class BeforeToolCall(
        val toolCall: ToolCall
    ) : AgentHookEvent

    /**
     * 每次工具调用后触发。
     *
     * @param synthetic true 表示该结果为合成占位（BeforeToolCall Refuse 短路产生），false 为真实工具执行结果
     * @param durationMs 工具实际执行耗时
     */
    public data class AfterToolCall(
        val toolCall: ToolCall,
        val result: ToolExecutionResult,
        val synthetic: Boolean,
        val durationMs: Long
    ) : AgentHookEvent {
        override fun copyWith(newResult: Any): HookEvent {
            return copy(result = newResult as? ToolExecutionResult
                ?: throw IllegalArgumentException(
                    "Hook returned HookResult.Modify for ${this::class.simpleName}, " +
                            "but newResult is ${newResult::class.qualifiedName}; " +
                            "${this::class.simpleName} requires newResult to be a ToolExecutionResult."
                )
            )
        }
    }

    /** Agent 成功完成时触发，对应 [AgentResult] 正常返回。 */
    public data class RunCompleted(
        val result: AgentResult
    ) : AgentHookEvent

    /** Agent 执行失败时触发，error 为领域异常。 */
    public data class RunFailed(
        val error: AgentException
    ) : AgentHookEvent
}
