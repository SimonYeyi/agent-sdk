package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart

internal class ToolResultAdapter {
    /**
     * 遍历 messages，把所有含 media 的 [ChatMessage.ToolResult] 拆成
     * text-only ToolResult + 合成 User；其余消息原样返回。
     *
     * 只在请求边界做这个拆分, memory 始终保留原始多模态信息。
     */
    fun adapt(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.flatMap {
            if (it is ChatMessage.ToolResult) {
                it.adaptModality()
            } else {
                listOf(it)
            }
        }
    }
}

/**
 * 把含 media 的 [ChatMessage.ToolResult] 拆成 text-only ToolResult + 合成的 User。
 * file-private extension —— 仅被 [ToolResultAdapter.adapt] 使用,
 * 不暴露给其他 caller。
 */
private fun ChatMessage.ToolResult.adaptModality(): List<ChatMessage> {
    val mediaParts = parts.filter { it !is ContentPart.Text }
    if (mediaParts.isEmpty()) return listOf(this)
    val textParts = parts.filterIsInstance<ContentPart.Text>()
    val textOnly = copy(parts = textParts)
    return listOf(
        textOnly,
        ChatMessage.User(parts = listOf(ContentPart.Text("[from $toolName]")) + mediaParts),
    )
}
