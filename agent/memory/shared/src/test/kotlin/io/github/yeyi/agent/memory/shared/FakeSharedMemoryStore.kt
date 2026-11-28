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

    /**
     * 让指定 name 的**删除被拒**:transform 返回 null 时文件仍在,modify 原样返回 current。
     *
     * 模拟 `PathSharedMemoryStore` 在 Windows 上 `File.delete()` 返回 false 的情形
     * (杀软 / 索引器短暂持句柄)。"请求过删除"与"真的删掉了"不是一回事,
     * 调用方必须按**返回值**判定,否则快照会比磁盘少一条。
     */
    var refuseDelete: Set<String> = emptySet()

    /** 在 [modify] 执行 transform 之前触发,用于模拟同名并发写入(如 reload 淘汰期间的 touch)。 */
    var onBeforeModify: (suspend (String) -> Unit)? = null

    /** 测试断言用:直接看当前落盘内容。 */
    fun contentOf(name: String): String? = files[name]

    /** 测试预置用:直接放入一条内容,绕过 modify。 */
    fun put(name: String, content: String) {
        files[name] = content
    }

    override suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? =
        locks.computeIfAbsent(name) { Mutex() }.withLock {
            onBeforeModify?.invoke(name)
            val current = files[name]
            val next = transform(current)
            if (next != null) {
                files[name] = next
                return@withLock next
            }
            // 删除请求:被拒时文件不改、返回 current(等价于"什么都没发生"),与真实 store 一致
            if (name in refuseDelete) return@withLock current
            files.remove(name)
            null
        }

    override suspend fun read(name: String): String? {
        if (name in failOnRead) error("boom: $name")
        return files[name]
    }

    override suspend fun exists(name: String): Boolean = files.containsKey(name)

    override suspend fun list(): List<String> =
        files.keys.filter { it != SharedMemory.RESERVED_USER && MEMORY_NAME_PATTERN.matches(it) }
}
