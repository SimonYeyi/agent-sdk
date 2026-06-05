package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class InMemoryMemory : Memory {
    private val messages: MutableList<ChatMessage> = mutableListOf()
    private val mutex: Mutex = Mutex()

    override suspend fun add(message: ChatMessage): Unit = mutex.withLock {
        messages += message
    }

    override suspend fun history(): List<ChatMessage> = mutex.withLock {
        messages.toList()
    }

    override suspend fun clear(): Unit = mutex.withLock {
        messages.clear()
    }
}
