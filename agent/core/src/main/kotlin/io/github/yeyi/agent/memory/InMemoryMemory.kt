package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

public class InMemoryMemory : Memory, MediaArchivable {
    override val mediaArchive: MediaArchive = InMemoryMediaArchive()

    private val entries: MutableList<MemoryEntry> = mutableListOf()
    private val mutex: Mutex = Mutex()

    override suspend fun add(entry: MemoryEntry): Unit = mutex.withLock {
        entries += entry
    }

    override suspend fun history(): List<MemoryEntry> = mutex.withLock {
        entries.toList()
    }

    override suspend fun rebuild(entries: List<MemoryEntry>): Unit = mutex.withLock {
        this.entries.clear()
        this.entries.addAll(entries)
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
