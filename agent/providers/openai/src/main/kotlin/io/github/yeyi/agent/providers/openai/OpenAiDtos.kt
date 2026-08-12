package io.github.yeyi.agent.providers.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null
)

@Serializable
internal data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean
)

@Serializable
internal data class OpenAiMessage(
    val role: String,                                                     // "system" | "user" | "assistant" | "tool"
    val content: OpenAiContent? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
internal data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiFunction
)

@Serializable
internal data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
internal data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall
)

@Serializable
internal data class OpenAiFunctionCall(
    val name: String,
    val arguments: String                                                  // 注意 OpenAI 把 arguments 序列化为 string
)

@Serializable
internal data class OpenAiChatResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null
)

@Serializable
internal data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// 流式 SSE 单帧
@Serializable
internal data class OpenAiStreamChunk(
    val id: String? = null,
    val choices: List<OpenAiStreamChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
internal data class OpenAiStreamChoice(
    val index: Int = 0,
    val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class OpenAiStreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiStreamToolCall>? = null
)

@Serializable
internal data class OpenAiStreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiStreamFunctionCall? = null
)

@Serializable
internal data class OpenAiStreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
internal sealed class OpenAiContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : OpenAiContentPart()

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        val url: String,
        @SerialName("detail") val detail: String? = null
    ) : OpenAiContentPart()

    @Serializable
    @SerialName("input_audio")
    data class InputAudio(
        val data: String,
        val format: String
    ) : OpenAiContentPart()
}

@Serializable
internal sealed class OpenAiContent {
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : OpenAiContent()

    @Serializable
    @SerialName("parts")
    data class PartsValue(val value: List<OpenAiContentPart>) : OpenAiContent()
}
