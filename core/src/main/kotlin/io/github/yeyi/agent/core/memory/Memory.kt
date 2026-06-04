package io.github.yeyi.agent.core.memory

import io.github.yeyi.agent.core.llm.ChatMessage

public interface Memory {
    public suspend fun add(message: ChatMessage)
    public suspend fun history(): List<ChatMessage>
    public suspend fun clear()
}
