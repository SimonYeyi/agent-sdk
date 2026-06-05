package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.llm.ToolCall
import kotlinx.serialization.Serializable

@Serializable
sealed class UiMessage {
    @Serializable
    data class User(val text: String) : UiMessage()

    @Serializable
    data class Assistant(
        val text: String,         // 流式累积的最终文本
        val toolCalls: List<ToolCall> = emptyList(),
    ) : UiMessage()

    @Serializable
    data class Error(val cause: String) : UiMessage()
}
