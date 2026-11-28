package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 共享记忆加载器,负责扫描目录、条数超额时淘汰、维护 name → SharedMemory 快照。
 *
 * 与 CapabilityRegistry 不同:这里没有外部注册者,清单完全由目录扫描得到,
 * 因此不提供 register/remove,对外只有 [load] 读取 + [modify] 写入 + 只读查找;
 * 重建快照是与它们绑定的内部动作,不单独对外暴露。
 *
 * **internal**:宿主拿不到本类,也就不存在"忘记调 reload"或"调错顺序"的问题。
 *
 * - [reload] 是淘汰的唯一判定点:扫描根目录所有 `.md` 文件(排除 `user.md`),
 *   记忆条数超过 [maxFileCount] 时按 lastAccessedAt 由远到近删除,再用剩下的记忆整体替换快照。
 * - 没有时间维度的遗忘:条数没超就一条不删,所以快照里可能存在很久没被访问的记忆。
 * - [load] 是读出路径:在 per-name 锁内取正文并刷新访问时间,不判淘汰,完成后自动 [reload]。
 * - [modify] 是写入入口:插件与 save 工具都经此访问存储,完成后同样自动 [reload]。
 * - 快照只由本类维护,所以"重建快照"收在 [load] / [modify] 内部,调用方不可能漏掉。
 * - 已删除的文件从清单移除;新增的文件加入清单;变更的 frontmatter 反映到 description。
 * - 容错:单个文件读失败或内容损坏 → 跳过该项(不进快照、也不删),reload 不因单文件问题整体失败。
 * - 线程安全:reload 在 [mutex] 内重建不可变快照,查找直接读快照,无需加锁。
 * - **同一目录一份状态**:实例由 [of] 按目录在进程内共享。多个 Agent 挂同一 `directory` 时
 *   看到的是同一份快照(对方新建的记忆立刻出现在清单里)、同一套 per-name 锁、同一个条数上限。
 * - 构造器 `private`:唯一生产构造路径是 [of],模块内也无法另造一个绕过注册表的实例;测试走 [create]。
 */
internal class SharedMemoryLoader private constructor(
    private val memoryStore: SharedMemoryStore,
    /** 目录里最多保留的记忆条数;超出部分按 lastAccessedAt 淘汰。 */
    private val maxFileCount: Int,
) {
    private val mutex = Mutex()

    /** 不可变快照;reload 整体替换,读方无锁。 */
    @Volatile
    private var snapshot: Map<String, SharedMemory> = emptyMap()

    /** 当前可读的所有记忆(快照)。 */
    fun all(): List<SharedMemory> = snapshot.values.toList()

    /** 按 name 查找记忆(目前用于测试断言与 description 渲染)。 */
    fun get(name: String): SharedMemory? = snapshot[name]

    /**
     * 读取一条记忆的正文,并把 lastAccessedAt 刷新为 now。
     *
     * 不改业务内容,但**会写盘**:刷新时间戳只能靠 `render` 整体重写,所以文件 mtime 会变。
     * 这是"访问时间存在 frontmatter 里"的直接结果,不是异常(见 spec §8.1 第 9 条)。
     *
     * 不判淘汰(判定只在 [reload],所以即将因超条数被淘汰的记忆在删掉前依旧能读出)。
     * 正文在 per-name 锁内读得,即"读"那一刻磁盘上的内容,不会与并发 save 错位;
     * 刚被刷新过的 lastAccessedAt 也让它在随后的 [reload] 里排在淘汰队尾。
     * 完成后自动 [reload]:条目超出 maxFileCount 时淘汰其他最久未访问的记忆。
     *
     * @return 正文(不含 frontmatter);文件不存在(已被淘汰)时返回 null
     */
    suspend fun load(name: String): String? {
        var body: String? = null
        memoryStore.modify(name) { current ->
            if (current == null) return@modify null          // 文件不存在:什么也不做
            val text = SharedMemory.bodyOf(current)
            body = text
            SharedMemory.parse(name, current)
                .copy(lastAccessedAt = Instant.now())
                .render(text)
        }
        reload()   // 必须在 store.modify 返回(锁已释放)之后;本记忆刚被 touch,不会被删
        return body
    }

    /**
     * 写入路径的"锁内读-改-写":委托 [SharedMemoryStore.modify] 完成,随后自动 [reload]。
     *
     * Loader 是 store 与快照的唯一持有者,插件与 save 工具都经此访问存储,
     * 所以"写完顺手重建快照"放在这里,调用方无法遗漏。
     *
     * 实现约束:[reload] 会对淘汰候选再进一次 per-name 锁,嵌套会自锁——
     * 所以 reload 只能放在 store.modify 返回之后,不能放进 transform 里。
     */
    internal suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? {
        val result = memoryStore.modify(name, transform)
        reload()   // 本次写入的记忆 lastAccessedAt 刚刷新,排在淘汰队尾
        return result
    }

    /**
     * 重扫根目录:记忆条数超过 [maxFileCount] 则淘汰最久未访问的,重建快照。
     *
     * 由 [load] / [modify] 在操作完成后自动调用,不对外暴露:快照只由本类维护,
     * 外部无法也不能"只重建快照而不做读写",也就无法造成快照与磁盘脱节。
     *
     * 淘汰规则(只在超出条数时生效):
     * - 只数条数,不量字节:`load_shared_memory` 的 description 要列出全部记忆名,
     *   条数才是上下文里真正装不下的东西;单条的体积另由 save 时的压缩尽力收进预算(软上限,失败则原文照存)。
     *   `user.md` 不在 [SharedMemoryStore.list] 里,既不占名额也不参与淘汰。
     * - 按 lastAccessedAt 升序淘汰(最久未访问先删),平局按 name 升序,保证可断言。
     * - 每次只删到刚好回到 [maxFileCount] 条;条数上限 ≥ 1 由 DSL 的 require 保证,
     *   所以不会把自己删空。
     * - 候选进 per-name 锁内二次判定:若 lastAccessedAt 已被并发 touch 变新,放过它。
     *
     * 调用约束:不得在任何 per-name 锁内调用(会自锁)。
     */
    private suspend fun reload(): Unit = mutex.withLock {
        // 1. 全量读取:坏文件跳过(不占名额、不删)
        val metas = LinkedHashMap<String, SharedMemory>()
        for (name in memoryStore.list()) {
            val content = runCatching { memoryStore.read(name) }.getOrNull() ?: continue
            metas[name] = SharedMemory.parse(name, content)
        }

        // 2. 没超额:一条不删。这是常态——条数是唯一约束,不是时间
        if (metas.size <= maxFileCount) {
            snapshot = metas
            return@withLock
        }

        // 3. 超额:最久未访问的先走
        val evictOrder = metas.values.sortedWith(compareBy({ it.lastAccessedAt }, { it.name }))
        for (candidate in evictOrder) {
            if (metas.size <= maxFileCount) break

            val observed = candidate.lastAccessedAt
            var removed = false
            memoryStore.modify(candidate.name) { current ->
                if (current == null) return@modify null
                val latest = SharedMemory.parse(candidate.name, current)
                // 并发 touch 过(时间戳变新)→ 放过它,下次 reload 再说
                if (latest.lastAccessedAt.isAfter(observed)) return@modify current
                removed = true
                null
            }
            if (removed) metas.remove(candidate.name)
        }

        snapshot = metas
    }

    companion object {
        /** 目录 → loader。进程内共享:同一目录只有一个快照、一套锁、一个条数上限。 */
        private val instances = ConcurrentHashMap<String, SharedMemoryLoader>()

        /**
         * 按目录共享的单例入口(生产路径的唯一用法,DSL 只经此拿 loader)。
         *
         * key 用 [Path.toRealPath],避免 `./mem` 与 `/abs/mem` 被当成两个目录而各自持锁与快照;
         * 目录不存在时先创建(store 的读写本来也要求目录存在)。
         *
         * 条数上限是目录的属性(它决定目录里能放几条),所以只由首次注册决定;后续对同一目录传了不同的上限
         * 直接报错——静默忽略会变成"我明明传了 50 条却没生效"这种最难查的错。
         * 上限必传:默认值只写在公开的 [io.github.yeyi.agent.AgentBuilder.sharedMemory] 上,
         * 不让第二处默认值出现。
         *
         * 注册表进程内常驻、不做回收:目录数量级是"每用户 / 每 agent 一份"。
         */
        internal fun of(directory: Path, maxFileCount: Int): SharedMemoryLoader {
            Files.createDirectories(directory)
            val real = directory.toRealPath()
            // compute 对同一 key 原子:"检查已有上限 + 注册"不能被另一个线程插进去
            return instances.compute(real.toString()) { _, existing ->
                if (existing == null) {
                    SharedMemoryLoader(PathSharedMemoryStore(real), maxFileCount)
                } else {
                    require(existing.maxFileCount == maxFileCount) {
                        "directory $real is already used with maxFileCount=${existing.maxFileCount}, got $maxFileCount"
                    }
                    existing
                }
            }!!
        }

        /**
         * 仅供测试:绕过注册表,直接注入内存 store 与极小的条数上限(否则难以触发淘汰)。
         * 用 [SharedMemoryStore] 而非目录,测试也就不必碰磁盘。
         */
        internal fun create(memoryStore: SharedMemoryStore, maxFileCount: Int): SharedMemoryLoader =
            SharedMemoryLoader(memoryStore, maxFileCount)
    }
}
