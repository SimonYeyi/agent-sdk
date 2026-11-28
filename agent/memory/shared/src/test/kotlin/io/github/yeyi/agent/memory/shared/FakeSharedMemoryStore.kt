package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 [SharedMemoryStore],测试替身:不碰磁盘,但**同样实现 per-name 锁**。
 *
 * 锁不能省:Task 4 的并发用例（并发 touch 跳过淘汰、并发 save 串行）依赖它;
 * 用无锁替身会让这些用例在"实现有竞态"时依旧通过。
 */
internal class FakeSharedMemoryStore : SharedMemoryStore {
    private val files = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 让指定 name 的 [read] 抛异常,用于验证 reload 的单文件容错。 */
    var failOnRead: Set<String> = emptySet()

    /** 测试断言用:直接看当前落盘内容。 */
    fun contentOf(name: String): String? = files[name]

    /** 测试预置用:直接放入一条内容,绕过 modify。 */
    fun put(name: String, content: String) {
        files[name] = content
    }

    override suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? =
        locks.computeIfAbsent(name) { Mutex() }.withLock {
            val next = transform(files[name])
            if (next == null) files.remove(name) else files[name] = next
            next
        }

    override suspend fun read(name: String): String? {
        if (name in failOnRead) error("boom: $name")
        return files[name]
    }

    override suspend fun exists(name: String): Boolean = files.containsKey(name)

    override suspend fun list(): List<String> =
        files.keys.filter { it != SharedMemory.RESERVED_USER && MEMORY_NAME_PATTERN.matches(it) }
}
