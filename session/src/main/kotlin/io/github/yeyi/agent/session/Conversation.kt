package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage

/**
 * 对话记录只读接口，通过 [Session.conversation] 获取。
 */
public interface Conversation {
    /**
     * 获取对话消息
     *
     * @param page 分页页码，从1开始。传入 null 则不分页，返回所有消息（从旧到新）。
     */
    public suspend fun messages(page: Int? = null): List<ChatMessage>
}
