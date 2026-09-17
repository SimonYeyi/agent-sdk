package io.github.yeyi.agent.memory

/**
 * Memory 只读包装器，供 AgentContext 使用。
 * Hooks 通过此包装器只能读取 history，调用 add 会抛异常。
 */
internal class ReadOnlyMemory(private val delegate: Memory) : Memory {
    override val mediaArchive: MediaArchive get() = delegate.mediaArchive

    override suspend fun add(entry: MemoryEntry): Unit =
        throw UnsupportedOperationException("Can not modify memory")

    override suspend fun history(): List<MemoryEntry> = delegate.history()

    override suspend fun rebuild(entries: List<MemoryEntry>): Unit =
        throw UnsupportedOperationException("Can not modify memory")
}
