package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private val SseMapper: Json = Json { ignoreUnknownKeys = true }

/**
 * 把 OpenAI SSE 文本行流(已按行拆分)解码为 StreamEvent。
 * - "data: [DONE]" → Done(usage, finishReason)
 * - "data: {...}" 是 OpenAiStreamChunk
 * - 其他行(注释、空行)忽略
 *
 * 第一个见到 tool_call id 的 chunk 会先发 ToolCallStart,再发 ToolCallDelta,
 * 让消费方可以提前初始化 id/name/arguments 缓冲(spec §4.2 与 Anthropic decoder 对齐)。
 * `finishReason` 来自最后一个 chunk 的 `choices[*].finish_reason`,映射后挂到 Done 上。
 * Continuation ToolCallDelta events always carry the most-recently-seen tool call id (filled from `seenToolCallIds`).
 */
internal fun decodeOpenAiSseLines(lines: Flow<String>): Flow<StreamEvent> = flow {
    var lastUsage: Usage? = null
    var lastFinishReason: String? = null
    val seenToolCallIds = mutableSetOf<String>()
    lines.collect { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith(":")) return@collect
        if (!line.startsWith("data:")) return@collect
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return@collect
        if (payload == "[DONE]") {
            emit(StreamEvent.Done(usage = lastUsage, finishReason = mapFinishReason(lastFinishReason)))
            return@collect
        }
        val chunk: OpenAiStreamChunk = try {
            SseMapper.decodeFromString(OpenAiStreamChunk.serializer(), payload)
        } catch (t: Throwable) {
            emit(StreamEvent.Error(t))
            return@collect
        }
        chunk.usage?.let {
            lastUsage = Usage(it.promptTokens, it.completionTokens, it.totalTokens)
        }
        for (choice in chunk.choices) {
            // 覆盖式记录:每条 chunk 的 finishReason 都更新,留最后一个非空值
            // (OpenAI 通常只在最后一条非空;但万一中间出现也按最后一次为准)
            choice.finishReason?.let { lastFinishReason = it }
            val delta = choice.delta
            delta.content?.let {
                if (it.isNotEmpty()) emit(StreamEvent.ContentDelta(it))
            }
            delta.toolCalls?.forEach { tc ->
                val id = tc.id
                val name = tc.function?.name
                if (id != null && id !in seenToolCallIds) {
                    seenToolCallIds += id
                    emit(StreamEvent.ToolCallStart(id = id, name = name!!))
                }
                emit(StreamEvent.ToolCallDelta(
                    id = id ?: seenToolCallIds.lastOrNull(),
                    name = name,
                    argumentsDelta = tc.function?.arguments.orEmpty()
                ))
            }
        }
    }
}

/**
 * OpenAI 字符串 → FinishReason 映射。OpenAI 文档字符串包括
 * "stop" / "length" / "tool_calls" / "function_call" / "content_filter"。
 *
 * - `null` → [FinishReason.Stop]:协议层没有给出 finish_reason(空流或上游缺失字段),
 *   按"模型正常完成、未发起工具调用"处理——这是合法的非错误状态。
 * - 未匹配的非空字符串 → [FinishReason.Error]:协议字段值不在已知集合内,属于真正的
 *   协议异常,留给消费方显式感知。
 */
private fun mapFinishReason(s: String?): FinishReason = when (s) {
    null -> FinishReason.Stop
    "stop" -> FinishReason.Stop
    "tool_calls", "function_call" -> FinishReason.ToolCalls
    "length" -> FinishReason.Length
    else -> FinishReason.Error
}
