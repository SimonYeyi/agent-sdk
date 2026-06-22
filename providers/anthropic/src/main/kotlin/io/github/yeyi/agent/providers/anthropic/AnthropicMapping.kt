package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val Mapper: Json = Json { ignoreUnknownKeys = true }

private val EmptyParams: JsonElement =
    Mapper.parseToJsonElement("""{"type":"object","properties":{}}""")

internal fun mapToAnthropic(model: String, request: ChatRequest): AnthropicChatRequest {
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

            is ChatMessage.User -> messages.add(
                AnthropicMessage(
                    role = "user",
                    content = listOf(AnthropicContentBlock.Text(msg.content)),
                )
            )

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
                    content = msg.content,
                    isError = msg.isError,
                )
                messages.add(AnthropicMessage(role = "user", content = listOf(block)))
            }
        }
    }
    val tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
        val schema: JsonElement = when (val s = tool.parametersSchema) {
            is ToolParameters.Empty -> EmptyParams
            is ToolParameters.JsonSchema -> Mapper.parseToJsonElement(s.schema)
        }
        AnthropicTool(
            name = tool.name,
            description = tool.description,
            inputSchema = schema,
        )
    }
    return AnthropicChatRequest(
        model = model,
        system = systemPrompt,
        messages = messages,
        tools = tools,
        stream = false,
        maxTokens = request.maxTokens ?: 1024,
        temperature = request.temperature,
        stopSequences = request.stopSequences.takeIf { it.isNotEmpty() },
    )
}

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
