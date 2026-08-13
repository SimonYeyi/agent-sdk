package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val decoderJson = Json { ignoreUnknownKeys = true }

internal fun decodeAnthropicSse(lines: Flow<String>): Flow<ChatResponseEvent> = flow {
    var pendingEvent: String? = null
    val pendingData = StringBuilder()
    var lastStopReason: String? = null
    var lastUsage: Usage? = null
    var currentToolCallId: String? = null

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
                    emit(ChatResponseEvent.Error(e))
                    return@collect
                }
                when (event) {
                    "content_block_start" -> {
                        val contentBlock = parsed["content_block"]?.jsonObject
                        if (contentBlock?.get("type")?.jsonPrimitive?.content == "tool_use") {
                            val id = contentBlock["id"]?.jsonPrimitive?.content ?: return@collect
                            val name = contentBlock["name"]?.jsonPrimitive?.content ?: return@collect
                            currentToolCallId = id
                            emit(ChatResponseEvent.ToolCallStart(id = id, name = name))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = parsed["delta"]?.jsonObject ?: return@collect
                        val deltaType = delta["type"]?.jsonPrimitive?.content
                        when (deltaType) {
                            "text_delta" -> {
                                val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                emit(ChatResponseEvent.ContentDelta(text))
                            }
                            "input_json_delta" -> {
                                val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                emit(ChatResponseEvent.ToolCallDelta(
                                    id = currentToolCallId,
                                    name = null,
                                    argumentsDelta = partial
                                ))
                            }
                        }
                    }
                    "message_start" -> {
                        // message_start.message.usage 提供初始 input_tokens 与 output_tokens(=0)
                        val usage = parsed["message"]?.jsonObject?.get("usage")?.jsonObject
                        if (usage != null) {
                            val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull
                            val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull
                            if (input != null && output != null) {
                                lastUsage = Usage(
                                    promptTokens = input,
                                    completionTokens = output,
                                    totalTokens = input + output
                                )
                            }
                        }
                    }
                    "message_delta" -> {
                        val delta = parsed["delta"]?.jsonObject
                        lastStopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                        // message_delta.usage(若存在)的 output_tokens 是最终值,不是增量;
                        // input_tokens 与 message_start 一致,保持 promptTokens 不变。
                        val usage = parsed["usage"]?.jsonObject
                        if (usage != null) {
                            val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull
                            val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull
                            val current = lastUsage
                            if (output != null) {
                                val prompt = input
                                    ?: current?.promptTokens
                                    ?: 0
                                lastUsage = Usage(
                                    promptTokens = prompt,
                                    completionTokens = output,
                                    totalTokens = prompt + output
                                )
                            }
                        }
                    }
                    "message_stop" -> {
                        val finish = when (lastStopReason) {
                            "end_turn" -> FinishReason.Stop
                            "max_tokens" -> FinishReason.Length
                            "tool_use" -> FinishReason.ToolCalls
                            else -> FinishReason.Stop
                        }
                        emit(ChatResponseEvent.Done(usage = lastUsage, finishReason = finish))
                    }
                    "content_block_stop" -> {
                        currentToolCallId = null
                    }
                    // ping / 其他 → 忽略
                    else -> {}
                }
            }
        }
    }
}
