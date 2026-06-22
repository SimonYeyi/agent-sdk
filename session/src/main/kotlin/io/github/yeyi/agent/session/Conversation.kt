package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage

public interface Conversation {
    public fun messages(page: Int? = null): List<ChatMessage>
}
