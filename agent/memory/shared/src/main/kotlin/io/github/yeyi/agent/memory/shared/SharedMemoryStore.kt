package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 记忆名 → 文件名的一层校验:字符合集 `[A-Za-z0-9._-]`、首字符非符号、长度 ≤ 64。
 *
 * name 来自 LLM,不校验就是路径穿越——`../../x` 能把文件写到 `directory` 之外。
 * 工具层用它给出友好错误,store 层再用 `require` 兜底一道。
 */
internal val MEMORY_NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/**
 * 共享记忆文件存储抽象。v1 只有 [PathSharedMemoryStore] 一个实现。
 *
 * **internal**:存储是模块内部的可替换缝(单测可换内存实现),宿主不感知也不注入。
 *
 * 并发语义:[modify] 对同一 'name' 串行执行——transform 在 per-name 锁内运行,
 * 使 save/load/压缩等复合操作(read → 计算 → write)对同一 name 原子化,
 * 消除单进程内的 lost update 与覆盖竞态。不同 name 之间可并行。
 *
 * [exists] 与 [list] 为只读,无需串行。
 */
internal interface SharedMemoryStore {
    /**
     * 在 per-name 锁内读取当前内容,交给 [transform] 计算新内容,再原子写回。
     *
     * - [transform] 收到当前 md 全文(frontmatter + 正文);文件不存在时收到 null
     * - [transform] 返回新内容;**返回 null 表示删除文件**(不是"无操作")
     * - 返回内容与当前内容**完全相同**(含同为 null)时视为无变更:不落盘、不刷新 mtime、不产生任何 IO。
     *   失败路径因此可以用 `return@modify current` 表达"什么都不做",既省掉一次无谓写入,
     *   也不会让落盘异常(如 Windows 上 rename 被拒)盖掉调用方要返回的业务错误
     * - 删除请求也可能失败(Windows 上目标被句柄占):此时不改文件,而是原样返回 `current`,
     *   等价于"什么都没发生"。调用方必须用**返回值**判定是否真的删掉了,不要用自己请求了什么
     * - transform 内部可执行 suspend 操作(如调 LLM 压缩),全程持锁,不会被同名操作插入
     * - 返回值为最终写入(或删除前的)内容,供调用方使用
     */
    suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String?

    /**
     * 只读当前内容,不加锁。文件不存在返回 null。
     * 用于 reload 等扫描场景——读到的可能是旧值或新值(reload 是最终一致快照,可接受)。
     * load/save 等需要原子性的路径应走 [modify],不要用 read + 外部 write 组合。
     */
    suspend fun read(name: String): String?

    suspend fun exists(name: String): Boolean

    /**
     * 所有 `.md` 文件名(不含后缀),即 reload 的条数统计与淘汰候选集。
     *
     * 过滤三件事:排除 [SharedMemory.RESERVED_USER]、只保留符合 [MEMORY_NAME_PATTERN] 的名字、忽略 `.tmp` 残留。
     */
    suspend fun list(): List<String>
}

/**
 * 基于 [Path] 的 [SharedMemoryStore] 默认实现。
 *
 * - 文件名:`$directory/$name.md`,`name` 先过 [MEMORY_NAME_PATTERN] 校验
 * - 目录必须已存在:建目录只由 [SharedMemoryLoader.of] 负责(它要先建好才能 `toRealPath` 作 key),
 *   这里不自建——两处 mkdir 会掩盖"谁负责建目录",且 `File.mkdirs()` 失败只返回 false,
 *   把原因静默吞掉,直到读写时才以空清单 / `NoSuchFileException` 的形式暴露
 * - IO 走 [Dispatchers.IO]
 * - `user.md` 文件存在但不列入 [list] (避免被 loader 当作普通记忆参与条数统计与淘汰)
 * - 非记忆文件不参与:名字不符合 [MEMORY_NAME_PATTERN] 的 `.md` 与 `.tmp` 残留都不进 [list],
 *   因此既不占名额也不会被淘汰(宿主可以在同目录放其他文件)
 * - per-name 互斥:[locks] 为每个 name 维护一把 [Mutex],[modify] 全程持锁,
 *   使 save 与压缩、save 与 load 对同一 name 串行。
 *   (store 由按目录单例的 [SharedMemoryLoader] 持有,所以同一目录全进程只有这一套锁。)
 * - write 走原子写入:先写 `.tmp` 再 [Files.move] 带 [StandardCopyOption.ATOMIC_MOVE] 替换目标,
 *   避免写一半中断导致文件损坏(残留的 `.tmp` 不会被 [list] 当成记忆,也不影响读取)
 * - 内容未变不落盘:[modify] 在 'transform' 返回与当前内容相同的字符串时直接返回,不写 `.tmp`。
 *   稳态的 `user.md` 初始化(原样返回当前值)与 save 的失败路径因此都是零 IO
 */
internal class PathSharedMemoryStore(private val directory: Path) : SharedMemoryStore {

    // 按 name 累积、不回收：见 spec §8.1 第 10 条。
    // 禁止在淘汰时 remove —— 与 computeIfAbsent 之间没有原子性，
    // 会让"刚拿到的锁被移除、第三方又造一把"，同名并发写重新变得可能。
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun fileOf(name: String): File {
        require(MEMORY_NAME_PATTERN.matches(name)) { "invalid memory name: $name" }
        return directory.resolve("$name.md").toFile()
    }

    private suspend fun <T> withNameLock(name: String, block: suspend () -> T): T =
        locks.computeIfAbsent(name) { Mutex() }.withLock { block() }

    override suspend fun modify(
        name: String,
        transform: suspend (current: String?) -> String?,
    ): String? = withNameLock(name) {
        val target = fileOf(name)
        val current = withContext(Dispatchers.IO) { if (target.exists()) target.readText() else null }
        val result = transform(current)
        // 内容未变(含"文件本来就不存在"):不产生任何写入。省掉一次无谓的 write + rename,
        // 也让失败路径的 return@modify current 真正无副作用。
        if (result == current) return@withNameLock result
        if (result == null) {
            // 删除也要按"是否真的发生"回答:delete 失败(Windows 上句柄被占)时文件仍在,
            // 此时原样返回 current(=没有变化),调用方(淘汰)才不会把还在磁盘上的条目
            // 从快照里摘掉、造成快照与磁盘脱节
            val deleted = withContext(Dispatchers.IO) { target.delete() }
            return@withNameLock if (deleted) null else current
        }
        withContext(Dispatchers.IO) {
            val tmp = directory.resolve("$name.md.tmp")
            Files.write(tmp, result.toByteArray(Charsets.UTF_8))
            Files.move(
                tmp, target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }
        result
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val f = fileOf(name)
        if (f.exists()) f.readText() else null
    }

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        fileOf(name).exists()
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        directory.toFile()
            .listFiles { f ->
                f.extension == "md" &&
                    f.nameWithoutExtension != SharedMemory.RESERVED_USER &&
                    MEMORY_NAME_PATTERN.matches(f.nameWithoutExtension)   // 非法名字(中文 / 空格)一律当非记忆文件
            }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
