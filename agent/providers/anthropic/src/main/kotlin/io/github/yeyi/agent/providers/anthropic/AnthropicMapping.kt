package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.llm.text
import kotlin.math.max

internal fun mapToAnthropic(
    model: String,
    request: ChatRequest,
    thinking: Boolean = false,
): AnthropicChatRequest {
    // Anthropic 协议把 system 提升为顶层字段, 且 messages 数组只接受 user/assistant 角色。
    // 所有 ChatMessage.System 在此拼接为单一 system 字符串, 用空行分隔;原列表中的 System
    // 不再进入 messages 数组(否则 API 可能会返回 400)。
    val systemPrompt: String? = request.messages
        .filterIsInstance<ChatMessage.System>()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n\n") { it.content }

    val messages = mutableListOf<AnthropicMessage>()
    request.messages.forEach { msg ->
        when (msg) {
            is ChatMessage.System -> Unit

            is ChatMessage.User -> {
                val blocks = msg.parts.map(::mapContentPart)
                messages.add(AnthropicMessage(role = "user", content = blocks))
            }

            is ChatMessage.Assistant -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                val assistantContent: String? = msg.content
                if (assistantContent != null) blocks.add(AnthropicContentBlock.Text(assistantContent))
                msg.toolCalls.forEach { call ->
                    blocks.add(AnthropicContentBlock.ToolUse(call.id, call.name, call.arguments))
                }
                messages.add(AnthropicMessage(role = "assistant", content = blocks))
            }

            is ChatMessage.ToolResult -> {
                val block = AnthropicContentBlock.ToolResult(
                    toolUseId = msg.toolCallId,
                    content = msg.parts.text,
                    isError = msg.isError,
                )
                messages.add(AnthropicMessage(role = "user", content = listOf(block)))
            }
        }
    }
    val tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
        AnthropicTool(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.parametersSchema,
        )
    }
    // Anthropic 要求 thinking enabled 时 max_tokens 必须大于 budget_tokens，否则 API 返回 400。
    // 未显式传 maxTokens 时兜底为 budget + 输出余量，保证开启即合法。
    val maxTokens: Int = if (thinking) {
        val floor = DEFAULT_THINKING_BUDGET + DEFAULT_OUTPUT_TOKEN_MARGIN
        max(request.maxTokens ?: floor, floor)
    } else {
        request.maxTokens ?: 1024
    }
    return AnthropicChatRequest(
        model = model,
        system = systemPrompt,
        messages = messages,
        tools = tools,
        stream = false,
        maxTokens = maxTokens,
        temperature = request.temperature,
        stopSequences = request.stopSequences.takeIf { it.isNotEmpty() },
        thinking = thinking(thinking),
    )
}

/**
 * 把思考开关翻译为 Anthropic `thinking` 对象。
 * 默认 false 显式发 `{"type":"disabled"}`：Sonnet 5 等新模型默认开启自适应思考，
 * 必须显式关闭才能避免简单任务消耗思考 token；true 时发 `{"type":"enabled",...}`。
 */
private fun thinking(enabled: Boolean): AnthropicThinking =
    if (enabled) AnthropicThinking(type = "enabled", budgetTokens = DEFAULT_THINKING_BUDGET)
    else AnthropicThinking(type = "disabled")

/** 开启思考模式时默认的 token 预算。 */
private const val DEFAULT_THINKING_BUDGET: Int = 4096

/** thinking 开启时输出余量：max_tokens 必须大于 budget_tokens，默认兜底为 budget + 该余量。 */
private const val DEFAULT_OUTPUT_TOKEN_MARGIN: Int = 1024

internal fun mapAnthropicToCore(response: AnthropicChatResponse): ChatResponse {
    val text = response.content.filterIsInstance<AnthropicContentBlock.Text>()
        .joinToString("") { it.text }
        .takeIf { it.isNotEmpty() }
    val toolCalls = response.content.filterIsInstance<AnthropicContentBlock.ToolUse>()
        .map { ToolCall(it.id, it.name, it.input) }
    val finishReason = when (response.stopReason) {
        "end_turn" -> FinishReason.Stop
        "max_tokens" -> FinishReason.Length
        "tool_use" -> FinishReason.ToolCalls
        else -> FinishReason.Stop
    }
    val usage = response.usage?.let {
        Usage(
            promptTokens = it.inputTokens,
            completionTokens = it.outputTokens,
            totalTokens = it.inputTokens + it.outputTokens,
        )
    }
    return ChatResponse(
        message = ChatMessage.Assistant(
            content = text,
            toolCalls = toolCalls,
        ),
        finishReason = finishReason,
        usage = usage,
    )
}

private fun mapImageToAnthropic(source: MediaSource): AnthropicContentBlock.Image.Source =
    when (source) {
        is MediaSource.Http -> AnthropicContentBlock.Image.UrlSource(source.url)
        is MediaSource.Data -> AnthropicContentBlock.Image.Base64Source(
            mediaType = source.mimeType,
            data = source.base64
        )

        is MediaSource.FileId -> AnthropicContentBlock.Image.FileSource(source.id)
        is MediaSource.Local -> throw AgentException.UnsupportedContent(
            "Anthropic does not accept Local media; resolve to Data via ModalityAdapter first"
        )
    }

private fun mapAudioToAnthropic(source: MediaSource): AnthropicContentBlock.Image.Source =
    mapImageToAnthropic(source)

private fun mapVideoToAnthropic(source: MediaSource): AnthropicContentBlock.Image.Source =
    when (source) {
        is MediaSource.Http -> AnthropicContentBlock.Image.UrlSource(source.url)
        is MediaSource.FileId -> AnthropicContentBlock.Image.FileSource(source.id)
        is MediaSource.Data -> throw AgentException.UnsupportedContent(
            "Anthropic does not support video base64 inline; use Http or FileId"
        )

        is MediaSource.Local -> throw AgentException.UnsupportedContent(
            "Anthropic does not accept Local media; resolve to Data via ModalityAdapter first"
        )
    }

private fun mapContentPart(part: ContentPart): AnthropicContentBlock = when (part) {
    is ContentPart.Text -> AnthropicContentBlock.Text(part.text)
    is ContentPart.Image -> AnthropicContentBlock.Image(mapImageToAnthropic(part.source))
    is ContentPart.Audio -> AnthropicContentBlock.Audio(mapAudioToAnthropic(part.source))
    is ContentPart.Video -> AnthropicContentBlock.Video(mapVideoToAnthropic(part.source))
}
