package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val decoderJson = Json { ignoreUnknownKeys = true }

internal fun decodeAnthropicSse(lines: Flow<String>): Flow<StreamEvent> = flow {
    var pendingEvent: String? = null
    val pendingData = StringBuilder()
    var lastStopReason: String? = null

    lines.collect { line ->
        when {
            line.startsWith("event: ") -> {
                pendingEvent = line.removePrefix("event: ").trim()
            }
            line.startsWith("data: ") -> {
                if (pendingData.isNotEmpty()) pendingData.append('\n')
                pendingData.append(line.removePrefix("data: "))
            }
            line.isBlank() -> {
                val event = pendingEvent
                val data = pendingData.toString()
                pendingEvent = null
                pendingData.clear()
                if (event == null || data.isEmpty()) return@collect
                val parsed = try {
                    decoderJson.parseToJsonElement(data).jsonObject
                } catch (e: Throwable) {
                    return@collect
                }
                when (event) {
                    "content_block_start" -> {
                        val contentBlock = parsed["content_block"]?.jsonObject
                        if (contentBlock?.get("type")?.jsonPrimitive?.content == "tool_use") {
                            val id = contentBlock["id"]?.jsonPrimitive?.content ?: return@collect
                            val name = contentBlock["name"]?.jsonPrimitive?.content ?: return@collect
                            emit(StreamEvent.ToolCallStart(id = id, name = name))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = parsed["delta"]?.jsonObject ?: return@collect
                        val deltaType = delta["type"]?.jsonPrimitive?.content
                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                emit(StreamEvent.ContentDelta(text))
                            }
                            "input_json_delta" -> {
                                val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                emit(StreamEvent.ToolCallDelta(id = null, name = null, argumentsDelta = partial))
                            }
                        }
                    }
                    "message_delta" -> {
                        val delta = parsed["delta"]?.jsonObject
                        lastStopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                    }
                    "message_stop" -> {
                        val finish = when (lastStopReason) {
                            "end_turn" -> FinishReason.Stop
                            "max_tokens" -> FinishReason.Length
                            "tool_use" -> FinishReason.ToolCalls
                            else -> FinishReason.Stop
                        }
                        emit(StreamEvent.Done(usage = null, finishReason = finish))
                    }
                    // ping / message_start / content_block_stop / 其他 → 忽略
                    else -> {}
                }
            }
        }
    }
}
