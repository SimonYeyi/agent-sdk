package io.github.yeyi.agent.providers.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AnthropicChatRequest(
    val model: String,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicTool>? = null,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = false,
)

@Serializable
internal data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: List<AnthropicContentBlock>,
)

@Serializable
internal sealed class AnthropicContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement,
    ) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : AnthropicContentBlock()
}

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

@Serializable
internal data class AnthropicChatResponse(
    val id: String,
    val model: String,
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)

@Serializable
internal data class AnthropicErrorResponse(
    val type: String? = null,
    val error: AnthropicErrorBody? = null,
)

@Serializable
internal data class AnthropicErrorBody(
    val type: String? = null,
    val message: String,
)
