package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage

/**
 * 对话历史存储接口，Agent 在多轮对话中通过它读写历史消息。
 *
 * 实现者需保证线程安全：ReActAgent 可能并发调用多个 suspend 方法。
 *
 * SDK 内部使用 [RoundsBoundedMemory] 装饰此接口，实现历史轮次上限和摘要压缩。
 */
public interface Memory {
    /**
     * 添加一条消息到历史。
     *
     * @param message 支持 [ChatMessage.User]、[ChatMessage.Assistant]、[ChatMessage.ToolResult] 等
     */
    public suspend fun add(message: ChatMessage)

    /**
     * 返回完整对话历史，按时间顺序排列。
     *
     * 返回的消息列表会被拼入 [io.github.yeyi.agent.llm.ChatRequest.messages] 传给 LLM。
     */
    public suspend fun history(): List<ChatMessage>

    /**
     * 用给定消息列表整体替换当前历史。
     *
     * 用于 Memory 实现内部的压缩/摘要重建场景；调用方不应随意调用。
     */
    public suspend fun rebuild(messages: List<ChatMessage>)
}
