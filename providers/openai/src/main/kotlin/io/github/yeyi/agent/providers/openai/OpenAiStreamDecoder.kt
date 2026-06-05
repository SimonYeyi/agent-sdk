package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

private val SseMapper: Json = Json { ignoreUnknownKeys = true }

/**
 * 把 OpenAI SSE 文本行流(已按行拆分)解码为 StreamEvent。
 * - "data: [DONE]" → Done
 * - "data: {...}" 是 OpenAiStreamChunk
 * - 其他行(注释、空行)忽略
 */
internal fun decodeOpenAiSseLines(lines: Flow<String>): Flow<StreamEvent> = flow {
    var lastUsage: Usage? = null
    lines.collect { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith(":")) return@collect
        if (!line.startsWith("data:")) return@collect
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return@collect
        if (payload == "[DONE]") {
            emit(StreamEvent.Done(lastUsage))
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
            val delta = choice.delta
            delta.content?.let {
                if (it.isNotEmpty()) emit(StreamEvent.ContentDelta(it))
            }
            delta.toolCalls?.forEach { tc ->
                emit(StreamEvent.ToolCallDelta(
                    id = tc.id,
                    name = tc.function?.name,
                    argumentsDelta = tc.function?.arguments.orEmpty()
                ))
            }
        }
    }
}
