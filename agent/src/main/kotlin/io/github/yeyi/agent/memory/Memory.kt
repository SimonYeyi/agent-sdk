package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage

public interface Memory {
    public suspend fun add(message: ChatMessage)
    public suspend fun history(): List<ChatMessage>
    public suspend fun rebuild(messages: List<ChatMessage>)
}
