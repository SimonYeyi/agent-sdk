package io.github.yeyi.agent.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class InMemoryMemory : Memory {
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
}
