package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * 全局"日志 hook":把 agent 全生命周期事件写到 SDK 内部的 [Logging] 通道。
 *
 * 行为:
 * - 纯观察:**永远不**短路任何工具调用([beforeToolCall] 永远返回 `null`)
 * - **永远不**改写工具结果([afterToolCall] 永远原样返回 input `result`)
 * - 在 [CompositeHook] 中组合时,放在前/后位置决定日志输出的相对顺序
 */
internal class LoggingHook : Hook {

    private val log = Logging.hook()

    override suspend fun beforeLlmCall(context: AgentContext) {
        log.debug("beforeLlmCall $context")
    }

    override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
        log.debug("afterLlmResponse toolCalls=${response.message.toolCalls.size} $context")
    }

    override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
        log.debug("beforeToolCall id=${call.id} name=${call.name} $context")
        return null
    }

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult {
        log.debug("afterToolCall id=${call.id} name=${call.name} dur=${durationMs}ms isError=${result.isError} $context")
        return result
    }

    override suspend fun onError(context: AgentContext, cause: AgentException) {
        log.warn("onError ${cause::class.simpleName}: ${cause.message} ${context}")
    }

    override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
        log.info("onRunFinished toolCalls=${result.toolCalls.size} $context")
    }

    override suspend fun onSessionCreated(session: Session) {
        log.info("onSessionCreated id=${session.id} name=${session.name}")
    }

    override suspend fun onSessionDeleted(userId: String, sessionId: String) {
        log.info("onSessionDeleted userId=$userId sessionId=$sessionId")
    }
}