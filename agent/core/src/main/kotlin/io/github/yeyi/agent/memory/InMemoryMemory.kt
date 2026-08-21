package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

public class InMemoryMemory : Memory {
    override val mediaArchive: MediaArchive = InMemoryMediaArchive()

    private val messages: MutableList<ChatMessage> = mutableListOf()
    private val mutex: Mutex = Mutex()

    override suspend fun add(message: ChatMessage): Unit = mutex.withLock {
        messages += message
    }

    override suspend fun history(): List<ChatMessage> = mutex.withLock {
        messages.toList()
    }

    override suspend fun rebuild(messages: List<ChatMessage>): Unit = mutex.withLock {
        this.messages.clear()
        this.messages.addAll(messages)
    }

    /**
     * 内存测试用 archive —— 直接存 base64 字符串,跳过 store 的 decode
     * 和 resolve 的 encode。内存场景下不需要还原 bytes —— base64 占内存
     * 略多但省两次编解码开销。
     */
    private class InMemoryMediaArchive : MediaArchive {
        private val store: MutableMap<String, String> = mutableMapOf()
        override suspend fun store(data: MediaSource.Data): MediaSource.Local {
            val fileId = UUID.randomUUID().toString()
            store[fileId] = data.base64
            return MediaSource.Local(fileId, data.mimeType)
        }
        override suspend fun resolve(local: MediaSource.Local): MediaSource.Data {
            val base64 = store[local.fileId]
                ?: throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
            return MediaSource.Data(local.mimeType, base64)
        }
    }
}
