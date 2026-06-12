package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * 全局"日志 hook":把 agent 全生命周期事件写到 SDK 内部的 [Logging] 通道。
 *
 * 行为:
 * - 纯观察:**永远不**短路任何工具调用([beforeToolCall] 永远返回 `null`)
 * - **永远不**改写工具结果([afterToolCall] 永远原样返回 input `result`)
 * - 在 [CompositeHook] 中组合时,放在前/后位置决定日志输出的相对顺序
 */
public class LoggingHook : Hook {

    private val log = Logging.hook()

    override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
        log.warn("iter=$iteration beforeLlmCall messages=${messages.size}")
    }

    override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
        log.warn("iter=$iteration afterLlmResponse toolCalls=${response.message.toolCalls.size}")
    }

    override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
        log.warn("beforeToolCall id=${call.id} name=${call.name}")
        return null
    }

    override suspend fun afterToolCall(
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult {
        log.warn("afterToolCall id=${call.id} name=${call.name} dur=${durationMs}ms isError=${result.isError}")
        return result
    }

    override suspend fun onError(cause: AgentException) {
        log.warn("onError: ${cause::class.simpleName}: ${cause.message}")
    }

    override suspend fun onRunFinished(result: AgentResult) {
        log.warn("onRunFinished iter=${result.iterations} toolCalls=${result.toolCalls.size}")
    }
}