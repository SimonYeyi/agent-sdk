package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.internal.Logging
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
 *
 * 子类化:[LoggingHook] 是 `open`,允许覆盖特定回调以注入自定义格式
 * (例如 JSON 结构化日志),其余方法继承默认实现。
 */
public open class LoggingHook : Hook {

    override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
        Logging.warn(
            "LoggingHook",
            "iter=$iteration beforeLlmCall messages=${messages.size}",
        )
    }

    override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
        Logging.warn(
            "LoggingHook",
            "iter=$iteration afterLlmResponse toolCalls=${response.message.toolCalls.size}",
        )
    }

    override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
        Logging.warn(
            "LoggingHook",
            "beforeToolCall id=${call.id} name=${call.name}",
        )
        return null
    }

    override suspend fun afterToolCall(
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult {
        Logging.warn(
            "LoggingHook",
            "afterToolCall id=${call.id} name=${call.name} dur=${durationMs}ms isError=${result.isError}",
        )
        return result
    }

    override suspend fun onError(iteration: Int, cause: Throwable) {
        Logging.warn(
            "LoggingHook",
            "iter=$iteration onError: ${cause::class.simpleName}: ${cause.message}",
        )
    }

    override suspend fun onRunFinished(result: AgentResult) {
        Logging.warn(
            "LoggingHook",
            "onRunFinished iter=${result.iterations} toolCalls=${result.toolCalls.size}",
        )
    }
}