package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage

/**
 * Memory 只读包装器，供 AgentContext 使用。
 * Hooks 通过此包装器只能读取 history，调用 add/clear 会抛异常。
 */
internal class ReadOnlyMemory(private val delegate: Memory) : Memory {
    override suspend fun add(message: ChatMessage): Unit =
        throw UnsupportedOperationException("Can not modify memory")

    override suspend fun history(): List<ChatMessage> = delegate.history()

    override suspend fun clear(): Unit =
        throw UnsupportedOperationException("Can not modify memory")
}