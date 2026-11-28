# 共享记忆插件设计

> 日期：2026-11-26 · 状态：**Proposed**
> 模块：`agent/memory/shared`（新增）
> 范围：新增一个独立的共享记忆插件,与 core 中的短期会话 Memory 正交;LLM 通过 `load_shared_memory` / `save_shared_memory` 两个工具读写 markdown 记忆文件,每轮主动注入 `user.md` 用户画像到 system prompt,并提供条数淘汰与压缩机制。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | shared-memory |
| 关联模块 | `agent/memory/shared`（新增） |
| 关联前置 | `AgentPlugin` / `Tool` / `ToolExecutionContext.agentContext.llmProvider` |
| 破坏性变更 | **否**——纯新增模块,不改动现有模块 |

---

## 1. 动机

`agent/core` 现有的 `Memory` 接口是对话历史存储(短期、按轮次压缩),解决"当前会话内 LLM 能记得刚才说过什么"的问题。它不解决跨会话的共享记忆:用户的偏好、过往事实、跨会话累积的画像,这些信息应当在多次会话之间持久化,且 LLM 能在合适时机主动调用。

需求要点:
1. 记忆以 markdown 文件形式存储,一个文件一个记忆,与 Skill 的 `load` 机制对称——LLM 看到 `load_shared_memory` 工具的 description 里列出所有记忆名+描述,需要时传 `name` 调用,正文按需加载进上下文。
2. `user.md` 作为与具体记忆无关的用户画像,每轮主动注入 system prompt,不走 `load_shared_memory` 路径。
3. 记忆的"该写什么、什么时候写"由 LLM 自主决定——只有模型能判断什么值得记。对应工具是 `save_shared_memory`。
4. 共享记忆需要体积管理:单条记忆超过 `maxFileCharSize`(字符)则同步压缩(压缩失败则原文照存,见 §6.3);目录里记忆条数超过 `maxFileCount` 时,按 `lastAccessedAt` 由远到近淘汰。
5. 存储位置由宿主决定(必传 `directory`),插件不做平台推断。

与现有 `Memory` 的区别:
- `Memory` 是 LLM 线格式消息的历史(`MemoryEntry`),按轮次/字节压缩,生命周期是单次会话。
- 共享记忆是 markdown 文件,按记忆组织,按时间/大小做生命周期,生命周期跨会话。
- 两者正交,互不依赖。

---

## 2. 设计原则

- **独立实现,不依赖 capability 框架**:共享记忆是内部生成的(由 `SharedMemoryLoader` 扫描目录构建),不需要外部宿主或插件提供 `Capability` 实例,与 Skill 的"外部注册"形态本质不同。`SharedMemoryPlugin` 直接实现 `AgentPlugin<SharedMemoryPluginConfig>`(`SharedMemoryPluginConfig` 是 object 占位,满足 `C : Any` 约束),`load_shared_memory` / `save_shared_memory` 两个工具都是普通 `Tool`,在 `install` 里手工 `registerTool`。不继承 `CapabilityPlugin`、不调 `CapabilityAdapter`、不依赖 `CapabilityLoadTool`。
- **公开面最小**:对外只暴露一个扩展函数 `AgentBuilder.sharedMemory(...)`。`SharedMemory` / `SharedMemoryLoader` / `SharedMemoryStore` / `PathSharedMemoryStore` / 两个 Tool / `SharedMemoryPlugin` / `SharedMemoryPluginConfig` 全部 `internal`——它们是同一模块内的实现细节,宿主不需要也不应该感知。将来要开放(如平台存储适配)再逐个提权。
- **写入决策在 LLM**:不暴露写入 API 给宿主代码,只有 `save_shared_memory` 工具。宿主只负责传 `directory` 与可选的 `maxFileCount` / `maxFileCharSize` / `LlmProvider`。
- **存储抽象最小**:`SharedMemoryStore` 是模块内部的可替换缝(单测可换内存实现),v1 只有 `PathSharedMemoryStore` 一个实现,不对外暴露。不预先做 Android/iOS 平台适配。
- **分层清晰**:`SharedMemory` 是纯值对象(元数据 + md 编解码),不持 store、无 IO;`SharedMemoryStore` 只管文本存取;`SharedMemoryLoader` 独占持有 store,负责扫描、条数淘汰与读写入口(锁内读 + touch、`modify`)。依赖方向只有 `Loader → SharedMemory / Store`。
- **淘汰由条数触发,不由时间触发**:没有"多久没读写就删"这种规则——一条重要但久未提及的记忆不该因为用户两个月没聊天而消失。只在记忆条数超过 `maxFileCount` 时,才按 `lastAccessedAt` 由远到近淘汰到回到条数内。判定点仍只有 `SharedMemoryLoader.reload()` 一处,由 `load` / `modify` 完成操作后自动触发;压缩仍并入 `save_shared_memory` 流程同步触发。
- **不要磁盘字节维度**:记忆清单必须写进 `load_shared_memory` 的 description,条数天然不允许太多,二十条 × 几千字的目录永远撑不满磁盘。"能装多少字节"不是真实约束,"清单多长、单条多大"才是。所以两个参数各管一件事:`maxFileCount` 管目录里有几条(`description` 清单长度 + 淘汰),`maxFileCharSize` 管单条多大(上下文/token + 触发压缩)。注意后者是**软上限**:压缩失败时超长正文仍会落盘(见 §6.3),真正硬拦截的只有"超过上限 4 倍直接拒绝写入"。
- **同一目录一份状态**:`SharedMemoryLoader.of(directory)` 按目录在进程内单例。共享记忆本身就意味着"同一个目录 = 同一份记忆",所以**快照、per-name 锁、条数上限都得跟着目录唯一**,而不是跟着 Agent 实例——否则两个 Agent 挂同一目录时,per-name 锁各一把(写可能互相覆盖),A 新建的记忆 B 的清单永远发现不了。条数上限冲突时直接报错(见 §4.2)。
- **包名独立**:新模块包名 `io.github.yeyi.agent.memory.shared`,避开 core 已有的 `io.github.yeyi.agent.memory`(防止同包跨模块冲突)。

---

## 3. 模块结构

新模块 `:agent:memory:shared`,路径 `agent/memory/shared/`。

- 需在 `settings.gradle.kts` 里加 `include(":agent:memory:shared")`。
- `build.gradle.kts` 按 `agent/skill` 模板: `kotlin("jvm")` + `jvmToolchain(21)` + `explicitApi()`;但**不需要** `kotlin.serialization` 插件(没有 `@Serializable` 类,只用 `JsonElement` 运行时 API)。
- 仅 JVM:依赖 `java.nio.file.Path` / `java.time`(Android 侧需 API 26+)。

文件清单:

```
agent/memory/shared/
├── build.gradle.kts
└── src
    ├── main/kotlin/io/github/yeyi/agent/memory/shared/
    │   ├── SharedMemory.kt          # internal data class SharedMemory(纯值对象:元数据 + md 编解码,无 IO)
    │   ├── SharedMemoryPlugin.kt    # internal class SharedMemoryPlugin : AgentPlugin<SharedMemoryPluginConfig>
    │   ├── SharedMemoryLoader.kt    # internal class:按目录单例(of) / 扫描 / 条数淘汰 / 读写入口(内部自动 reload)
    │   ├── SharedMemoryStore.kt     # internal interface SharedMemoryStore + internal PathSharedMemoryStore
    │   ├── SharedMemoryLoadTool.kt  # internal class,工具 load_shared_memory
    │   ├── SharedMemorySaveTool.kt  # internal class,工具 save_shared_memory(含同步压缩)
    │   └── SharedMemoryExtensions.kt # public fun AgentBuilder.sharedMemory(...) —— 唯一的公开入口
    └── test/kotlin/io/github/yeyi/agent/memory/shared/
        └── (各组件单元测试)
```

依赖关系(全部**不**向上游泄漏:模块对外 API 只有 `Path` / `LlmProvider`):
- `api(project(":agent:core"))` — `AgentBuilder` / `AgentPlugin` / `Tool` / `AgentContext.llmProvider`(core 已 `api` 导出 coroutines / serialization)
- `implementation(libs.kotlinx.serialization.json)` — 只用于工具参数 `JsonElement`
- `implementation(libs.kotlinx.coroutines.core)` — per-name `Mutex`、`runBlocking`
- `testImplementation(libs.kotlin.test)` / `libs.kotlin.test.junit` / `libs.kotlinx.coroutines.test` / `libs.junit`(与 `agent/skill` 一致)

---

## 4. 核心类型

### 4.1 `SharedMemory` —— 记忆实体(纯值对象 + 文件编解码)

`SharedMemory` 同时承担两个职责:一个记忆的元数据(`name` / `description` / `createdAt` / `lastAccessedAt`),以及整个 `.md` 文件的解析与渲染(正文以 `String` 参数在 `parse` / `bodyOf` / `render` 之间流转)。不再有独立的 `SharedMemoryFile` 类型——实体与文件是同一回事。

**它是纯值对象:不持 `SharedMemoryStore`,没有任何 IO 能力。** 读取(锁内读正文 + 刷新 `lastAccessedAt`)由 [`SharedMemoryLoader.load`] 执行。两个好处:`parse` 是纯函数、可单测;依赖方向只剩 `Loader → SharedMemory / Store` 单向,不再有实体回指 store。

**正文不驻留在实体上**:快照只缓存元数据,正文永远以锁内读到的最新内容为准。否则快照里的正文会成为一份无人读取、又可能与磁盘不一致的副本。

```kotlin
package io.github.yeyi.agent.memory.shared

import java.time.Instant

/**
 * 一个共享记忆 —— 纯值对象:元数据 + 对 `.md` 文件全文(frontmatter + 正文)的解析/渲染能力。
 *
 * 不持有 [SharedMemoryStore],没有任何 IO:本类只回答"这段文本是什么记忆"和
 * "把记忆渲染回文本",读写编排由 [SharedMemoryLoader] 负责。
 *
 * 由 [SharedMemoryLoader] 在 reload 时解析构建,不依赖外部宿主注册。
 *
 * **internal**:纯粹是模块内实现细节,宿主不感知本类型(公开面只有一个 DSL 函数)。
 *
 * - "按需加载":description 告诉 LLM 何时使用,正文不预加载
 * - 正文不在实体上驻留,而是在 [parse] / [bodyOf] / [render] 之间以 String 流转
 * - 淘汰判断只由 `SharedMemoryLoader.reload` 做(条目超出 `maxFileCount` 时按 [lastAccessedAt] 由远到近)
 * - `data class`:纯值对象,自带 `copy` / `equals` / `hashCode`。刷新访问时间用
 *   `copy(lastAccessedAt = ...)`,不原地修改
 */
internal data class SharedMemory(
    /** 记忆名(等于文件名,不含 .md)。解析时以文件名为准。 */
    val name: String,
    /** 给 LLM 看的"何时使用本记忆"提示。 */
    val description: String,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 最后一次被 load/save 的时间。只用作条数淘汰时的排序依据(最久未访问的先删)。 */
    val lastAccessedAt: Instant,
) {
    /**
     * 用本元数据 + [body] 渲染 md 全文,写回文件时使用。
     *
     * 记忆文件只由本模块读写,**不是给人编辑的入口**:每次读写都按下列格式整体重写,
     * 手工加入的字段 / 注释 / 缩进 / 换行风格都会在下次读写时被规范化(甚至被丢弃)。
     *
     * frontmatter 固定 4 行,值永远单行:`name` / `description` 渲染前把换行折成空格
     * (description 是给 LLM 看的一句话,不需要多行);时间用 `Instant.toString()`(ISO-8601)。
     * 不做 YAML 引号转义——解析端按"第一个 `:` 切分 + trim"读回,两边规则必须对称。
     */
    fun render(body: String): String { /* "---" + frontmatter + "---" + body */ }

    companion object {
        /** 保留名:跨会话用户画像。不参与记忆清单,也不允许 LLM 直接 load。 */
        const val RESERVED_USER: String = "user"

        /**
         * description 的长度上限。
         *
         * description 不只是文件元数据:它每轮都被 `load_shared_memory` 的 tool description
         * 拼成 "- name:description" 下发,是常驻上下文成本。正文有 `maxFileCharSize` 兜底,
         * 这里必须对称地收住,否则一条失控的描述会在之后每一轮里重复付费。
         */
        const val MAX_DESCRIPTION_CHARS: Int = 200

        /**
         * 纯函数:解析 md 全文的 frontmatter 构建元数据。[name] 取文件名(不含 .md),
         * 与 frontmatter 中的 name 冲突时以文件名为准。不抛异常。
         *
         * 缺字段兜底原则是"宁可当新,不要排到淘汰队首":
         * - 缺 `createdAt` → 用 `lastAccessedAt`;缺 `lastAccessedAt` → 用 `createdAt`
         * - 两者都缺或时间戳无法解析 → 用解析时刻 now(而不是 EPOCH,否则它永远是条数淘汰的第一个)
         * - 缺 `description` → 空串
         * - `description` 超过 [MAX_DESCRIPTION_CHARS] → 截断(外部写入的 frontmatter 不受本模块控制)
         * - 没有 frontmatter(整段都是正文)→ 全部兜底,全文作正文
         */
        fun parse(name: String, content: String): SharedMemory { /* 手写 frontmatter,非 YAML 库 */ }

        /** 取正文(去掉 frontmatter 后的部分);没有 frontmatter 时返回全文。 */
        fun bodyOf(content: String): String { /* frontmatter 之后的文本 */ }

        /**
         * 新建一个记忆(user.md 初始化用)。
         *
         * description 在此截断到 [MAX_DESCRIPTION_CHARS]:这里是"由外部文本(md frontmatter /
         * LLM 入参)构造记忆"的唯一入口,上限收在入口,下游(渲染、清单、淘汰)就不必各自防御。
         */
        fun create(name: String, description: String, now: Instant): SharedMemory =
            SharedMemory(
                name = name,
                description = description.take(MAX_DESCRIPTION_CHARS),
                createdAt = now,
                lastAccessedAt = now,
            )
    }
}
```

### 4.2 `SharedMemoryLoader` —— 扫描、条数淘汰与快照

```kotlin
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
     * 这是"访问时间存在 frontmatter 里"的直接结果,不是异常(见 §8.1 第 9 条)。
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
            if (current == null) return@modify null
            val text = SharedMemory.bodyOf(current)
            body = text
            SharedMemory.parse(name, current)
                .copy(lastAccessedAt = Instant.now())
                .render(text)
        }
        reload()  // 必须在 store.modify 返回(锁已释放)之后;本记忆刚被 touch,不会被删
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
        reload()  // 本次写入的记忆 lastAccessedAt 刚刷新,排在淘汰队尾
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
     * - 快照按 name 升序构建:清单顺序不随文件系统枚举顺序变,`load_shared_memory` 的
     *   工具描述因此逐轮稳定(不白白打乱 prompt 前缀)。
     * - 每次只删到刚好回到 [maxFileCount] 条;条数上限 ≥ 1 由 DSL 的 require 保证,
     *   所以不会把自己删空。
     * - 候选进 per-name 锁内二次判定:若 lastAccessedAt 已被并发 touch 变新,放过它。
     *
     * 调用约束:不得在任何 per-name 锁内调用(会自锁)。
     */
    private suspend fun reload(): Unit = mutex.withLock {
        // 1. 全量读取:坏文件跳过(不占名额、不删)
        val metas = LinkedHashMap<String, SharedMemory>()
        for (name in memoryStore.list().sorted()) {
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
            // 用**返回值**判定是否真的删掉了:transform 返回 null 是删除请求,但并发 touch
            // 会返回 current(放过它),store 删盘失败也返回 current(文件还在)。
            // 只有拿到 null 才从快照里摘掉,否则快照会与磁盘脱节。
            val after = memoryStore.modify(candidate.name) { current ->
                if (current == null) return@modify null
                val latest = SharedMemory.parse(candidate.name, current)
                // 并发 touch 过(时间戳变新)→ 放过它,下次 reload 再说
                if (latest.lastAccessedAt.isAfter(observed)) return@modify current
                null
            }
            if (after == null) metas.remove(candidate.name)
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
         * 上限必传:默认值只写在公开的 [AgentBuilder.sharedMemory] 上,不让第二处默认值出现。
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
```

### 4.3 `SharedMemoryPlugin` —— 接线契约

**直接实现 `AgentPlugin`**:不继承 `CapabilityPlugin`、不调 `CapabilityAdapter`、不依赖 `CapabilityLoadTool`。`load_shared_memory` / `save_shared_memory` 两个工具都是普通 `Tool`,在 `install` 里手工 `registerTool`。`SharedMemoryPluginConfig` 是 object 占位,满足 `AgentPlugin<C : Any>` 的 `C : Any` 约束。

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * 占位 config 类型,满足 [AgentPlugin] 的 `C : Any` 约束。
 *
 * SharedMemoryPlugin 的所有配置在构造器完成,configure block 为 no-op。
 */
internal object SharedMemoryPluginConfig

/**
 * 共享记忆插件的接线类。
 *
 * 直接实现 [AgentPlugin],install 阶段完成:
 * 1. 经 loader.modify(RESERVED_USER) 确保 `user.md` 存在(不存在则写空正文模板)
 * 2. refreshUserProfile(userBody) 注入 `user.md` 正文到 persona 尾部独立段落(正文为空则跳过)
 * 3. registerTool(SharedMemoryLoadTool(loader))  → load_shared_memory
 * 4. registerTool(SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile))
 *    → save_shared_memory,同时把刷新入口注入给工具
 *
 * **运行期刷新（user 永远最新）**:插件保留 [AgentPluginContext] 引用，把
 * `addInstruction(新正文, "user-profile")` 包装成回调交给 save 工具；工具写完 user 成功后调用它，
 * 把最新正文**原位替换**进 system prompt 段落。因此 user 永远等于"磁盘上那一刻的正文"，
 * 不需要 turn hook，也不需要每轮重读:[ReActAgent.buildRequest] 每轮都重新 `persona.toString()`，
 * 运行期 `addInstruction` 立即生效。
 *
 * 插件不持有 [SharedMemoryStore]、也不调 reload():存储、快照与条数淘汰都由 [SharedMemoryLoader]
 * 独占维护,读写入口内部自动重建快照。
 */
internal class SharedMemoryPlugin(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) { /* no-op,配置已在构造器完成 */ }

    override fun install(context: AgentPluginContext) {
        // user 的刷新入口:save 写成功后调它,把最新正文原位替换进 persona。
        // label 固定 "user-profile":addInstruction 遇同名段落即原位替换,不追加。
        // 在这里捕获 context(而不是让工具去拿),契约最窄:工具只知道"user 变了,该通知一下"。
        val refreshUserProfile: (String) -> Unit = { newBody ->
            context.addInstruction(newBody, "user-profile")
        }

        val userBody = runBlocking { initialize() }  // ensureUserMemory;内部 loader.modify 已自动 reload
        // 注入 user.md 正文到 persona 尾部独立段落(具名 label,便于后续原位刷新)。
        // 不在这里加标题/占位:"存在一个可写的 user 记忆"由 save_shared_memory 的 description 告知,
        // 每轮随 tools 下发,比 persona 里的固定文字更可靠。
        if (userBody.isNotBlank()) refreshUserProfile(userBody)

        // 注册两个工具(普通 Tool,不经过 CapabilityAdapter)
        context.registerTool(SharedMemoryLoadTool(loader))
        context.registerTool(
            SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile)
        )
    }

    // 不再显式调 reload():loader.modify 内部已完成快照重建 + 条数淘汰
    private suspend fun initialize(): String = ensureUserMemory()

    // 若 user.md 不存在,创建空模板;返回 user.md 正文(供 install 注入)
    private suspend fun ensureUserMemory(): String {
        val empty = SharedMemory.create(
            name = SharedMemory.RESERVED_USER,
            description = "用户画像,每轮主动注入 system prompt",
            now = Instant.now(),
        )
        // 已存在时 modify 会原样重写一次(原子替换),成本可接受;顺带的 reload 完成 install 首次条数检查
        val content = loader.modify(SharedMemory.RESERVED_USER) { current ->
            current ?: empty.render(body = "")
        } ?: return ""
        return SharedMemory.bodyOf(content)
    }
}
```

**注意 `runBlocking` 的使用**:`install` 是同步签名(`AgentPlugin.install` 非 suspend),但 `SharedMemoryStore` 的操作是 suspend。`runBlocking` 在 install 阶段阻塞直到完成,这是插件初始化的合理代价——install 在 Agent 构建期执行,不在 LLM 推理路径上。

### 4.4 `SharedMemoryLoadTool` —— 读出工具

普通 `Tool`,不继承 `CapabilityLoadTool`。description 每轮求值(用 getter 而非 `by lazy`),拼出当前 loader 快照中所有记忆的 name + description 列表,使 reload 后下一轮 LLM 自动看到新记忆。execute 只调一句 `loader.load(name)`(锁内读正文 + 刷新访问时间,不判淘汰;完成后 loader 内部自动 reload,条目超出 `maxFileCount` 时淘汰其他最久未访问的记忆)。

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class SharedMemoryLoadTool(
    private val loader: SharedMemoryLoader,
) : Tool {
    override val name: String = "load_shared_memory"

    override val description: String
        get() = buildString {
            appendLine("加载共享记忆。")
            appendLine("当前可用的记忆文件:")
            val memories = loader.all()   // 只取一次快照:判空与遍历必须是同一份
            if (memories.isEmpty()) {
                appendLine("(暂无)")
            } else {
                memories.forEach { m ->
                    appendLine("- ${m.name}：${m.description}")
                }
            }
            appendLine("传入 name 调用,记忆按需加载入上下文。")
        }.trimEnd()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val name = (arguments.jsonObject["name"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'name'")

        // 保留名拦截:user.md 不参与记忆清单,只通过 system prompt 暴露,不允许直接 load
        if (name == SharedMemory.RESERVED_USER) {
            return ToolExecutionResult.error(
                "'${SharedMemory.RESERVED_USER}' is the auto-injected user profile and is not in the memory list; " +
                    "its content is already in your system prompt. Use save_shared_memory to update it."
            )
        }

        // 锁内读正文 + 刷新 lastAccessedAt,不判淘汰;loader 随后自动 reload
        val content = loader.load(name)
            ?: return ToolExecutionResult.error("Memory not found: $name (it may have been forgotten)")

        return ToolExecutionResult.success(content)
    }
}
```

### 4.5 `SharedMemorySaveTool` —— auxiliary 工具

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * 压缩输入的容错倍数:`newBody` 超过 `maxFileCharSize` 的这个倍数、**且这次写入把它撑得更长**时,
 * 直接拒绝写入;把超限正文改短的写入一律放行(否则超限文件永久不可修)。
 *
 * compressMemory 要把全文塞进 LLM 的 prompt,连输入窗口都装不下的内容压不了;
 * 放行只会让磁盘上多一个永远超长的记忆,之后每次 load 都击穿上下文预算。
 */
private const val MAX_COMPRESS_INPUT_FACTOR = 4

/**
 * LLM 写入共享记忆的工具。
 *
 * 参数:
 * - name: 记忆名(对应文件名,不含 .md)。字符合集 `[A-Za-z0-9._-]`、≤ 64,与 store 的校验一致
 *   (不校验就会变成路径穿越:`../../x` 写出目录之外)
 * - description: 记忆描述(给 LLM 看的"何时使用本记忆"提示)。**新建记忆时必填**;已有记忆忽略此参数。
 *   超过 [SharedMemory.MAX_DESCRIPTION_CHARS] 时截断——它每轮随工具清单进上下文
 * - content: 写入内容。正文一行一条,所以这里也应是单行。两种模式都必须**显式给出**:
 *   键缺失一律报错;只有 update 下的显式空白串才表示删除该条目
 * - mode: "append" 在正文末尾新增一条条目 / "update" 用 content 整条替换掉一条旧条目。
 *   正文按行组织(一行一条),所以 old_text 必须与其中一整行完全相同,update 天然只命中一条;
 *   普通记忆改内容必须先 load 拿到正文再复制 old_text;用户画像 user 的正文常驻 system prompt,
 *   所以 update 天然是它的主路径。
 *
 * 副作用:
 * - 更新 frontmatter.lastAccessedAt(= now):写入即访问
 * - 写入后 loader.modify 内部自动 reload:重建快照(新记忆由此进入清单);若记忆条数超过 maxFileCount,
 *   按 lastAccessedAt 由远到近淘汰,直到回到条数内(本次写入的记忆刚被 touch,排在队尾)
 * - 写入后若 newBody 超过 maxFileCharSize(字符数,≈ token),先在 transform 内同步调 LLM 压缩,
 *   算完 finalBody 再一次性写入(与 write 同一把 per-name 锁,无竞态窗口)
 * - 写入的是 user 时,写成功后调 [onUserProfileUpdated] 把**最终正文**(可能已被压缩)原位替换进
 *   system prompt 的 user 段落。下一次 buildRequest 就看到新值——user 永远不会停在 install 快照上。
 *
 * **不判淘汰**:写入即视为访问。单条超出 maxFileCharSize 时先试压缩,但压缩可能失败——
 * 所以它是**软上限**(失败时原文照存并回执 WARNING);真正拦死超长入参的是"超过上限 4 倍
 * 且这次把正文撑得更长"。条数超额的淘汰只在随后的 reload 里发生,且针对的是**其他**最久未访问的记忆。
 *
 * 返回:写入确认信息。不回字符数(内部指标,模型无可行动的判断依据);
 * 但压缩成功/失败必须说明——磁盘上的内容可能不等于它写入的原文;删除条目单独成句,
 * 因为它和"写入成功"是两种结果。
 *
 * 并发语义:本工具自身不持锁,串行性由 [SharedMemoryStore] 的 per-name 互斥保证——
 * save 与 load、save 与压缩对同一 name 自动串行。存储经 [SharedMemoryLoader.modify] 访问。
 */
internal class SharedMemorySaveTool(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
    /** user 正文落盘后的回调(由插件注入,内部调 addInstruction 原位替换 persona 段落);测试可传 null。 */
    private val onUserProfileUpdated: ((String) -> Unit)? = null,
) : Tool {
    override val name: String = "save_shared_memory"

    override val description: String = """
        将信息写入共享记忆文件。当用户要求或你发现了值得跨会话长期记住的事实(偏好、背景、约定等)时调用本工具。

        调用前请先看 load_shared_memory 工具描述里列出的（已存在）记忆文件列表,选择要记录的内容归属于哪个记忆文件:
        - 有合适的记忆文件:传该记忆文件的 name,description 留空(记忆文件已存在，不需要描述)
        - 没有合适的记忆文件:传新 name + 必填的 description(一句话告诉未来的自己何时该加载本记忆文件)，由程序新建

        另有一个内置记忆 "user"(用户画像),它不出现在上面的列表里,内容每轮自动注入你的 system prompt:
        - 用户透露了稳定的偏好、背景、习惯、身份信息时,用 name="user" 写入,它会跨会话注入；
        - system prompt 里那段关于用户偏好/背景的文字就是 user 的现有内容,update 时引用其中原文作 old_text。

        参数:
        - name: 记忆文件名(英文短词,如 "travel-preferences")。
        - description: 记忆文件描述(≤200 字)。新建记忆文件时必填,选中合适的记忆文件时忽略。例:"用户旅行偏好与历史行程"
        - content: 记忆内容。正文按行组织,一行一条,所以 content 也应是单行;两种模式都必须给出:
                   append 模式下必填(不能是空白),作为新条目追加到正文末尾;
                   update 模式下作为该条目的新版本;只有显式传空串 "" 才是删除该条目,省略本字段会报错。
        - mode: "append" 或 "update",默认 "append"。
          - append: 在正文末尾新增一条条目。记忆文件新建时也走此模式(此时正文为空)。
          - update: 整条更新。old_text 必须与现有正文中的**一整行完全相同**(不只是其中几个字),
                    工具用 content 替换掉那一行。这是条目替换,不是词句替换;
                    要改多条就调多次,每次给一条。
        - old_text: 仅 update 模式必填。要被替换的那一整条条目,必须与正文中某一行完全一致
                    (含你写入时的标点与空格),不能为空串。不是关键词,也不是半句话。
    """.trimIndent()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","update"]},"old_text":{"type":"string"}},"required":["name"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val name = arguments.jsonObject["name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        if (!MEMORY_NAME_PATTERN.matches(name)) {
            return ToolExecutionResult.error("Invalid 'name': use [A-Za-z0-9._-] only, max 64 chars")
        }
        val description = arguments.jsonObject["description"]?.jsonPrimitive?.content
        val mode = arguments.jsonObject["mode"]?.jsonPrimitive?.content ?: "append"
        val content = arguments.jsonObject["content"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error(
                if (mode == "update") {
                    "Missing 'content': pass the new line, or an empty string to remove the entry"
                } else "Missing 'content'"
            )
        val oldText = arguments.jsonObject["old_text"]?.jsonPrimitive?.content
        // old_text 必须指向一段真实文本:空串能通过 contains 校验,而按它做替换会在
        // 每两个字符之间插入 content,把整篇正文毁掉
        if (mode == "update" && oldText.isNullOrBlank()) {
            return ToolExecutionResult.error("Missing or empty 'old_text': quote an existing passage verbatim")
        }
        // append 不接受空白内容:它会往正文里塞一个空行,而回执照旧是 Saved——
        // 攒几次之后正文开始多出无意义空行,且 update 的整行匹配永远碰不到它们
        if (mode == "append" && content.isBlank()) {
            return ToolExecutionResult.error("Missing 'content': append needs a non-blank entry line")
        }

        val llmProvider = llmProvider ?: context.agentContext.llmProvider
        val now = Instant.now()

        // 状态变量:由 transform 内部写入,execute 外层读取以决定返回值
        var notFound = false            // update 模式 old_text 找不到 / update 不存在 / 非法 mode
        var missingDescription = false  // 新建记忆但 description 为空
        var tooLarge = false            // 远超 maxFileCharSize 且本次写入又把它撑大 → 拒绝写入
        var compressed = false          // 超限且压缩成功(正文已被改写)
        var overLimitKept = false       // 超限但没压(压缩失败 / 输入太大没必要试),原文照存
        var deletedEntry = false        // update 模式下删掉了一条条目(回执要单独说明)
        var storedBody = ""             // 最终写入的正文(user 用它刷新 system prompt)

        loader.modify(name) { current ->
            val meta = if (current == null) {
                if (description.isNullOrBlank()) { missingDescription = true; return@modify current }
                SharedMemory.create(name, description, now)
            } else {
                // 已有记忆:description / createdAt 沿用旧值,忽略入参
                SharedMemory.parse(name, current)
            }
            val body = current?.let { SharedMemory.bodyOf(it) } ?: ""

            // 失败路径一律 return@modify current:store 把 null 视为"删除文件",
            // 只有原样返回才不会被误当成删除请求
            val newBody = when (mode) {
                // 一行一条:追加就是接一行
                "append" -> if (body.isBlank()) content else "$body\n$content"
                "update" -> {
                    // 条目级替换:old_text 必须与某一整行完全一致(trim 后比较)。
                    // 不按子串匹配——否则只引用几个字也会命中,结果改掉半句话
                    val target = oldText!!.trim()
                    val lines = body.lines()
                    val index = lines.indexOfFirst { it.trim() == target }
                    if (current == null || index < 0) { notFound = true; return@modify current }
                    // content 为空白 → 删除该条目(removeAt,不留空行);否则整行换成新版本
                    lines.toMutableList().apply {
                        if (content.isBlank()) { deletedEntry = true; removeAt(index) } else this[index] = content
                    }.joinToString("\n")
                }
                else -> { notFound = true; return@modify current }
            }

            // 压缩本身要把 newBody 整个塞进 LLM prompt,连输入窗口都装不下的内容压不了:
            // 放行只会落一个永远超长的文件、之后每次 load 都爆上下文预算。
            // 但硬门只拦"把正文撑大"的那一次写入:已经超限的记忆(宿主放进来的、或旧配置下
            // 压缩失败照存的)必须能靠 update 改短救回来,否则它永久不可修。
            if (newBody.length > maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR && newBody.length > body.length) {
                tooLarge = true
                return@modify current
            }

            // compressMemory 返回非 null 时已保证比原文短,无需再比长度。
            // 已超硬上限且这次没变长时不再尝试压缩:输入本身装不进 prompt,压也压不了,
            // 直接原样写入(软上限语义),让它先被改短,下一轮才有压缩的机会。
            val finalBody = when {
                newBody.length <= maxFileCharSize -> newBody
                newBody.length <= maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR ->
                    compressMemory(llmProvider, maxFileCharSize, newBody)?.also {
                        compressed = true
                    } ?: newBody.also { overLimitKept = true }
                else -> newBody.also { overLimitKept = true }
            }
            storedBody = finalBody          // 供 user 刷新 persona 段落用
            meta.copy(lastAccessedAt = now).render(finalBody)  // 写入即访问
        }

        when {
            tooLarge -> return ToolExecutionResult.error(
                "Content is over ${maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR} chars, too large to compress; " +
                    "split it into several saves of under $maxFileCharSize chars each"
            )
            missingDescription -> return ToolExecutionResult.error(
                "Missing 'description': required when creating a new memory '$name'"
            )
            notFound -> return ToolExecutionResult.error(
                if (mode == "update") {
                    "old_text not found: no line in '$name' matches it exactly; quote the whole entry line"
                } else "Invalid mode: $mode"
            )
        }

        // user 的正文就是 system prompt 的一部分:写成功后立即把最终正文(可能已被压缩)
        // 原位替换进 persona。这是 user "永远最新" 的唯一机制:不需要 turn hook,也不需要重读磁盘。
        // 压缩会改写正文,所以这里必须用落盘后的 finalBody,而不是调用方传入的 content。
        if (name == SharedMemory.RESERVED_USER) {
            onUserProfileUpdated?.invoke(storedBody)
        }

        // 回执只给模型能据此行动的信息:字符数不回;压缩/超限必须回,
        // 否则模型会以为磁盘上还是它写下的原文,后续 update 的 old_text 就对不上。
        // 删除单独成句:它和"写入成功"是两种结果,合成一句会让模型误以为条目还在。
        val note = when {
            compressed -> " Content was compressed to fit maxFileCharSize; the stored text is a rewritten summary, not what you sent."
            overLimitKept -> " WARNING: content exceeds maxFileCharSize and compression failed; it was stored as-is."
            else -> ""
        }
        val receipt = if (deletedEntry) "Removed one entry from memory '$name'." else "Saved memory '$name'."
        return ToolExecutionResult.success(receipt + note)
    }

    private suspend fun compressMemory(
        llmProvider: LlmProvider,
        maxFileCharSize: Int,
        body: String,
    ): String? {
        val targetChars = maxFileCharSize / 2
        val prompt = """
            请整理以下共享记忆内容,按以下规则压缩:
            1. 去重:相似信息只保留最后一份，但不是简单的裁切，而是语义融合
            2. 去冲突:语义相反信息以最后一份为准，去掉前面冲突的内容
            3. 合并:相关信息合并为更紧凑的表述(如"喜欢海鲜"+"喜欢日料"→"喜欢海鲜与日料")
            4. 保留:所有关键事实必须保留,不能因压缩丢失语义，且保持原有的记忆条目的格式与顺序

            只输出压缩后的正文,不要任何前缀说明。压缩到 $targetChars 字以内。

            原始内容:
            $body
        """.trimIndent()
        val request = io.github.yeyi.agent.llm.ChatRequest(
            messages = listOf(
                io.github.yeyi.agent.llm.ChatMessage.System("你是一个记忆整理助手,只负责按规则压缩用户提供的记忆内容,不回答其他问题。"),
                io.github.yeyi.agent.llm.ChatMessage.User(listOf(io.github.yeyi.agent.llm.ContentPart.Text(prompt))),
            ),
        )
        val summary = runCatching { llmProvider.chat(request).message.content }.getOrNull() ?: return null
        // 无效兜底:压缩结果没有变短(字符数),说明压缩失败,返回 null 让调用方保留原文
        return if (summary.length < body.length) summary else null
    }
}
```

### 4.6 `SharedMemoryStore` —— 存储抽象

```kotlin
package io.github.yeyi.agent.memory.shared

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * 并发语义:[modify] 对同一 [name] 串行执行——transform 在 per-name 锁内运行,
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
     * - [transform] 返回新内容;返回 null 表示删除文件
     * - 返回内容与当前内容完全相同(含同为 null)时视为无变更:不落盘、不刷新 mtime、不产生任何 IO。
     *   失败路径因此可以 return@modify current 表达"什么都不做",也避免落盘异常盖掉要返回的业务错误
     * - 删除请求也可能失败(Windows 上目标被句柄占):此时不改文件,而是原样返回 current,
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
 * - IO 走 [Dispatchers.IO]
 * - `user.md` 文件存在但不列入 list()(避免被 loader 当作普通记忆参与条数统计与淘汰)
 * - 非记忆文件不参与:名字不符合 [MEMORY_NAME_PATTERN] 的 `.md` 与 `.tmp` 残留都不进 list(),
 *   因此既不占名额也不会被淘汰(宿主可以在同目录放其他文件)
 * - per-name 互斥:[locks] 为每个 name 维护一把 [Mutex],[modify] 全程持锁,
 *   使 save 与压缩、save 与 load 对同一 name 串行。
 *   (store 由按目录单例的 [SharedMemoryLoader] 持有,所以同一目录全进程只有这一套锁。)
 * - write 走原子写入:先写 `.tmp` 再 [Files.move] 带 [StandardCopyOption.ATOMIC_MOVE] 替换目标,
 *   避免写一半中断导致文件损坏(残留的 `.tmp` 不会被 list() 当成记忆,也不影响读取)
 * - 内容未变不落盘:transform 返回与当前内容相同的字符串时直接返回,不写 `.tmp`。
 *   稳态的 user.md 初始化(原样返回当前值)与 save 的失败路径因此都是零 IO
 */
internal class PathSharedMemoryStore(private val directory: Path) : SharedMemoryStore {
    init { directory.toFile().mkdirs() }

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun fileOf(name: String): java.io.File {
        require(MEMORY_NAME_PATTERN.matches(name)) { "invalid memory name: $name" }
        return directory.resolve("$name.md").toFile()
    }

    private suspend fun <T> withNameLock(name: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(name) { Mutex() }
        return mutex.withLock { block() }
    }

    override suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? = withNameLock(name) {
        val target = fileOf(name)
        val current = withContext(Dispatchers.IO) {
            if (target.exists()) target.readText() else null
        }
        val result = transform(current)
        // 内容未变(含"文件本来就不存在"):不产生任何写入
        if (result == current) return@withNameLock result
        if (result == null) {
            // 删除也要按"是否真的发生"回答:delete 失败时文件仍在,原样返回 current(=没有变化),
            // 调用方(淘汰)才不会把还在磁盘上的条目从快照里摘掉
            val deleted = withContext(Dispatchers.IO) { target.delete() }
            return@withNameLock if (deleted) null else current
        }
        withContext(Dispatchers.IO) {
            // 原子写入:先写 .tmp,再 ATOMIC_MOVE 替换目标,避免写一半中断导致文件损坏
            val tmp = directory.resolve("$name.md.tmp")
            Files.write(tmp.toPath(), result.toByteArray(Charsets.UTF_8))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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
                    MEMORY_NAME_PATTERN.matches(f.nameWithoutExtension)  // 非法名字(中文 / 空格)一律当非记忆文件
            }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
```

### 4.7 `SharedMemoryExtensions` —— DSL

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path

/**
 * 注册共享记忆插件到 Agent —— **本模块唯一的公开 API**。
 *
 * 与 core 的 `AgentBuilder.memory(Memory, maxRounds)`(短期会话历史)区分开,所以不叫 `memory(...)`:
 * `sharedMemory` 是跨会话的 markdown 记忆,两者正交。
 *
 * @param directory 记忆文件根目录,宿主必传(不存在会自动创建)。
 *   同一目录在进程内共享同一份快照、同一套锁与同一个条数上限,多个 Agent 传同一目录即共享同一份记忆。
 * @param maxFileCount 目录里最多保留的记忆条数,超出则按最久未访问淘汰。默认 20
 * @param maxFileCharSize 单条记忆的字符数上限(≈ 上下文/token 预算),超则同步压缩。默认 8000
 *   注意这是**软上限**:压缩失败时超长正文照存;超过 `maxFileCharSize × 4` 且这次又把正文撑大的写入才直接拒绝。
 * @param llmProvider 压缩用 LLM;null 时回退到 AgentContext.llmProvider。默认 null
 */
public fun AgentBuilder.sharedMemory(
    directory: Path,
    maxFileCount: Int = 20,
    maxFileCharSize: Int = 8000,
    llmProvider: LlmProvider? = null,
) {
    require(maxFileCount > 0) { "maxFileCount must be positive: $maxFileCount" }
    require(maxFileCharSize > 0) { "maxFileCharSize must be positive: $maxFileCharSize" }
    plugin(
        SharedMemoryPlugin(
            loader = SharedMemoryLoader.of(directory, maxFileCount),
            maxFileCharSize = maxFileCharSize,
            llmProvider = llmProvider,
        )
    )
}
```

---

## 5. 数据流

### 5.1 install 阶段

```
AgentBuilder.sharedMemory(directory, maxFileCount, maxFileCharSize, llmProvider)
  → require 校验参数(maxFileCount 条 > 0、maxFileCharSize 字符 > 0)
  → SharedMemoryLoader.of(directory, maxFileCount)   # 按目录单例:createDirectories + toRealPath 作 key
                                                       # 已存在则复用;传了不同 maxFileCount 直接报错
  → plugin(SharedMemoryPlugin(loader, maxFileCharSize, llmProvider))
    → SharedMemoryPlugin.install(context)
      → runBlocking { initialize() }
        → ensureUserMemory()             # 经 loader.modify(RESERVED_USER) 建空模板;完成后 loader 内部已自动 reload
      → 构造 refreshUserProfile = { body -> context.addInstruction(body, "user-profile") }  # 保留 context;同名 label 即原位替换
      → if (userBody.isNotBlank()) refreshUserProfile(userBody)   # 首次注入也走同一条路径
      → context.registerTool(SharedMemoryLoadTool(loader))                    # load_shared_memory
      → context.registerTool(SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile))
                                                                           # save_shared_memory(带 user 刷新回调)
```

### 5.2 每轮 LLM 请求

```
ReActAgent 构造 ChatRequest
  → ToolRegistry.all().map { it.toDefinition() }
    → load_shared_memory.toDefinition()  # 读 description,触发 getter → 读 loader.all() → 列出所有记忆名+描述
                                          # 同目录共享的快照:别的 Agent 刚写的记忆也在里面
    → save_shared_memory.toDefinition()
  → ChatRequest.tools = [load_shared_memory, save_shared_memory, ...其他工具]
  → system prompt = persona.toString() 实时渲染,其中含 user 段落
                                          # 每次 buildRequest 都重算:save(user) 后下一轮立即生效
  → LLM 看到所有记忆名 + user 画像
```

### 5.3 LLM 调用 load_shared_memory(name="travel")

```
load_shared_memory.execute({name: "travel"})
  → loader.load("travel")
      → memoryStore.modify("travel") { current ->        # per-name 锁内
          text = bodyOf(current)                          # 锁内读到的最新正文,即本次返回值
          parse(current).copy(lastAccessedAt = now).render(text)   # 只刷新访问时间,不判淘汰
        }
      → 返回 text;文件不存在(已被淘汰)时返回 null
  → loader.load 内部自动 reload()                          # 条数超额则淘汰最久未访问的 + 重建快照
  → ToolExecutionResult.success(正文 / error "not found")
```

### 5.4 LLM 调用 save_shared_memory(name, content, mode)

```
save_shared_memory.execute({name, content, mode, old_text?})
  → 解析参数:name 必合法;content 必须**显式出现**(update 缺失即报错,只有显式空串才是删除);
    append 不接受空白 content;update 的 old_text 不能为空白
  → loader.modify(name) { current ->                     # per-name 锁内,整流程原子(经 loader 委托到 store)
      current == null → SharedMemory.create(name, description, now),body = ""
      current != null → parse,description / createdAt 沿用旧值
      mode=append: body + "\n" + content            # 正文一行一条,追加就是接一行
      mode=update:
        old_text 为空串 → 直接 error
        current == null 或没有任何一行与 old_text 完全相同(trim 后) → notFound,原样返回 current(不落盘)
        整行替换:用 content 替换那一行;content 为空白表示删除该行(不留空行,回执改为 Removed one entry from ...)
      不判淘汰:lastAccessedAt = now(写入即访问,本次写入排在淘汰队尾)
      若 newBody.length > maxFileCharSize × 4 且这次没把正文改短:→ 直接 error,不落盘(压缩输入都装不下)
      若 newBody.length > maxFileCharSize(字符):
        → 同步调 compressMemory(newBody)   # 仍在 modify 锁内
        → 压缩成功(summary 更短):用 summary 作 finalBody,compressed 标记
        → 压缩失败(null 或 summary 不变短):保留 newBody,overLimitKept 标记
        → 已超硬上限且被改短:不再试压(输入装不下),原样写入 + overLimitKept 标记
      返回 meta.copy(lastAccessedAt = now).render(finalBody)   # 原子写回
    }
  → loader.modify 内部自动 reload()                     # 新记忆进入快照 + 条数超额时淘汰其他最久未访问的
  → tooLarge / missingDescription / notFound → 返回对应 error(均不落盘)
  → name == "user" → onUserProfileUpdated(storedBody)    # 用落盘后的最终正文原位替换 persona 里的 user 段落
  → 返回 "Saved memory 'x'."(update 删条目时为 "Removed one entry from memory 'x'.") + 压缩说明 / 超限 WARNING / 无
```

### 5.5 同步压缩

```
compressMemory(llmProvider, maxFileCharSize, body)
  → llmProvider.chat(压缩到约 ${maxFileCharSize/2} 字以内)   # 字符口径;失败返回 null
  → runCatching { ... }.getOrNull()   # 失败返回 null,保留 newBody
  → 返回 summary
```

压缩不是独立协程,而是 save 流程在 write **之前**的一步:transform 内算完 finalBody 才一次性写入。
整段与 write 共享 store 的 per-name 锁,所以不存在"先落盘原文再回改"的中间态。
save 返回时文件已是最终态(压缩后或未压缩),下一轮 load 直接看到。

---

## 6. 边界情况

### 6.1 `user.md` 处理

- 创建:`install` 时若不存在,创建空模板(真实元数据 + 空正文)。
- 读取:不在记忆清单中(`SharedMemoryStore.list()` 排除 `user`),且 `SharedMemoryLoadTool` 对保留名直接返回错误——
  这是必需的:**`loader.load(name)` 不再经过快照**,快照排除 user 挡不住它,必须在工具层显式拦(直接读文件就能读到)。
- 写入:`save_shared_memory("user", ...)` 允许。append 用于累积新画像条目;update 用于"忘记 xxx"/"改一下 xxx"——正文一行一条,LLM 从 system prompt 中看到 user.md 现有内容,引用要改的**那一整行**作 old_text,提供 content 作新版本(空串表示删除该行)。user.md 不在 `list()` 里,LLM 不能 load,所以 update 的 old_text 只能凭 system prompt 中注入的内容引用——这正是下面"写时刷新"必须要成立的原因。
- 注入与刷新:两件事,一个 label(`user-profile`)。
  - `install` 时读 `user.md` 正文,`addInstruction(body, "user-profile")` 注入 persona 尾部独立段落;正文为空时不注入。
  - 每次 `save_shared_memory("user", ...)` **成功后**,插件用**落盘后的最终正文**(可能已被压缩)调 `addInstruction(新正文, "user-profile")` 原位替换该段落。工具通过构造器注入的 `onUserProfileUpdated` 回调通知插件,自己不知道 persona 的存在。
  - 关键依据:`AgentPersona.extra(content, label)` 对同名 label 是原位替换;`ReActAgent.buildRequest` 每轮重新 `persona.toString()`。所以运行期写一次,模型下一次请求就看到新值。
  - 结论:**user 永远与磁盘一致**,不依赖 turn hook,也没有"要等下一次会话"的窗口。
  - 正文被清空时注入空串(渲染为空段落)。`AgentPluginContext` 没有 remove 接口,而跳过刷新会让模型继续看到过期画像——空段落是两害中更轻的一个。
- 告知 `user` 可写不靠 persona:save 工具 description 里明确写了"另有一个内置记忆 user,不在列表里,用 name=`user` 写入"。工具描述每轮都随 `tools` 下发,不依赖正文是否为空。
- 淘汰豁免:`user.md` 不在 `list()` 中,reload 不数它也不淘汰它。

### 6.2 reload 的并发安全

- `SharedMemoryLoader.reload()` 用 `Mutex` 保护,重建后整体替换 `@Volatile` 快照。
- 触发点:`load()` / `modify()` 完成后自动调用;工具与插件不再自行触发。
- `SharedMemoryLoader.load()` 的读 + touch 走 store 的 per-name 锁,与同名 save 串行,返回值即锁内那一刻的磁盘内容。
- `reload` 内部的淘汰删除在 per-name 锁内二次判定:若 lastAccessedAt 已被并发 touch 变新则跳过,与并发 load/save 串行。
- 快照与磁盘一致:`all()` / `get()` 反映最近一次 reload 的结果;读方直接读快照,无锁;reload 期间旧快照仍可读,切换原子。
- **并发会话共享同一份状态**:`SharedMemoryLoader.of(directory)` 保证同一目录只存在一个 loader,所以多个 Agent 实例共享快照与 per-name 锁——A 新建的记忆,在 B 下一轮构造请求(读 `loader.all()`)时就出现了;两边写同一 name 也是串行。

### 6.3 压缩语义与并发

- **压缩位置**:压缩是 `save_shared_memory` 流程内的一步,在 `modify` 的 transform 里算完 finalBody 才一次性写入、然后才返回,与 write 共享 store 的 per-name 锁。不是独立协程。
- **失败语义**:压缩在两种情况下返回 null,save 流程保留 append/update 后的 newBody 作为最终 body:
  1. LLM 调用抛错(网络、限流等)
  2. LLM 返回的 summary 字符数 ≥ 原 body(压缩无效,没变短)
  下次 save 若 `newBody.length` 仍超 `maxFileCharSize` 会再次触发压缩——这是"压缩失败则直接追加,等下一次写入时再次触发"的落点。
- **超硬上限直接拒绝**:`newBody.length > maxFileCharSize × 4` **且这次没把正文改短**时不尝试压缩,直接返回错误、不落盘。理由是压缩本身要把全文塞进 LLM prompt,连输入窗口都装不下的内容压不了;放行只会让磁盘上多一个永远超长的记忆,之后每次 `load` 都击穿上下文预算。错误回执提示模型分段写入。
  反过来,已经超限的记忆(宿主放进来的、或旧配置下压缩失败照存的)必须能靠 `update` 改短救回来——否则它永久不可修。把超限正文改短的写入一律放行,且不再尝试压缩(输入同样装不下),按软上限语义直接落盘并带 WARNING。
- **`maxFileCharSize` 是软上限**:压缩失败时超长原文照存,而读路径(`loader.load`)不检查长度、也不静默截断(截断既丢信息,又让模型无从得知)。所以"单条不超 `maxFileCharSize`"只在压缩成功时成立。
- **回执对齐真实磁盘内容**:压缩成功 → 回执说明"存的是改写后的摘要";超限且压缩失败 → 回执带 WARNING 说"超长原文照存"。不回字符数。理由:压缩改写后磁盘内容就不再是模型写入的原文（后续 load 读到的是摘要）,不告知会让它以为原文完整存在。
- **并发**:save 与 load 全程在 `store.modify` 的 per-name 锁内执行,包含 read、压缩(LLM 调用)、write。同 name 操作串行,不同 name 可并行。单进程内不存在 lost update 或压缩覆盖竞态。
- **跨进程**:不支持。按目录的单例也只在进程内成立;若宿主跨进程共享同一 `directory`,须自行加协调层。

### 6.4 条数淘汰的语义

- **唯一判定点**:`SharedMemoryLoader.reload()`。`load()` 与 `SharedMemorySaveTool` 都不判淘汰。
- **触发时机**:由 loader 的读写入口自动触发——`load` / `modify` 完成各自操作后调一次 `reload()`。工具与插件都不感知。
- **只有超额才淘汰**:条数 ≤ `maxFileCount` 时一条都不删。"很久没访问"本身不构成删除理由,只在超出条数时才按 `lastAccessedAt` 由远到近腾地方。
- **计数口径**:目录里符合 `MEMORY_NAME_PATTERN` 的 `.md` 条数(`list()` 的结果);`user.md` 不在其中,既不占名额也不参与淘汰。
- **单条大小不参与淘汰**:一条很长但没超过 `maxFileCharSize` 的记忆不会被淘汰,压缩是它唯一的约束;反过来,压缩失败留下的超长记忆也不会因为"太大"而被删。
- **不会删空**:条数上限 ≥ 1 由 DSL 的 `require` 保证,循环条件 `metas.size <= maxFileCount` 一满足就停,所以只会删到刚好回到上限。
- **本次操作的记忆排在队尾**:load 刚把 lastAccessedAt 刷为 now,save 刚写入 now;reload 在 per-name 锁内二次判定,不与并发读写竞争。
- **删到刚好回到上限为止**:正常写入一次只多一条,所以一次 reload 只淘汰一条——写第 N+1 条 → 删最久未访问的那条 → 回到 N 条就停,下一条新记忆到来时才再淘汰一条。只有目录被外部一次性塞进多个 `.md` 时,才会在下次 reload 一次连删到刚好 N 条。两种路径都是"够用就停",不会连锁清空。
- **淘汰不回报**:save 的返回里不说淘汰了谁,但下一轮 `load_shared_memory` 的 description 会重新列出当前清单(改进方向见 §8.2)。
- **手工删文件同样生效**:宿主/用户直接删掉一个 `.md`,下一次 reload 后它就从清单消失,并且不再占名额。
- **没有后台巡检**:清理只随 `load` / `modify` 的 `reload()` 发生。宿主长时间不调工具时,目录可能一直超出条数,直到下一次读写才收敛。

### 6.5 与短期 Memory 的关系

- 完全正交,互不依赖。
- 共享记忆插件不读不写 `Memory`(短期对话历史)。
- 短期 `Memory` 不知道共享记忆的存在。
- 同一个 Agent 可以同时配置短期 `Memory` 和共享记忆插件,互不干扰。

---

## 7. 测试策略

### 7.1 单元测试

- `SharedMemory.parse / bodyOf / render`:frontmatter 解析的往返一致性(render 后 parse + bodyOf 能还原元数据与正文),且 `parse` 不依赖 store(纯函数、无 IO);缺失字段、乱序字段、特殊字符、文件名与 frontmatter name 冲突时以文件名为准。
  - 缺 `lastAccessedAt` 的文件解析后 **不得** 被排到淘汰队首(兜底用 now 而非 EPOCH)
  - 没有 frontmatter / 半截 frontmatter 的文件不抛异常,正文取全文
  - `description` 含换行 / 冒号 / 中文时 render → parse 往返后与原值一致(换行已折成空格)
  - `description` 超过 `MAX_DESCRIPTION_CHARS` → `create` 与 `parse` 都截断(外部写入的 frontmatter 也收住)
- `MEMORY_NAME_PATTERN`:接受 `travel` / `user` / `a.b-c_1`;拒绝 `../x`、`a/b`、空串、65 字符、`.hidden`(首字符非字母数字)。
- `PathSharedMemoryStore`:在 tempdir 上读写删列表,IO 异常路径;`list()` 只返回合法名——`中文.md` / `a b.md` / `x.md.tmp` / `user.md` 都不出现。
- `SharedMemoryLoader` 的条数淘汰(`reload` 已 private,经 `load` / `modify` 间接验证):条数未超额时**一条都不删**;超额时按 lastAccessedAt 由远到近删到刚好回到 `maxFileCount`;`user.md` 既不占名额也不淘汰;并发 touch 过的候选被跳过;单条超长但未超 `maxFileCharSize` 不影响淘汰;快照按 name 升序(清单顺序不随文件系统枚举顺序变)。
- `SharedMemoryLoader.load`:锁内取正文并刷新 lastAccessedAt,不判淘汰(即将被淘汰的文件在删掉前仍能读出);文件不存在返回 null 且不新建空文件。
- `SharedMemoryLoader.load` / `modify` 完成后快照即同步:调用返回后 `get()` / `all()` 立刻反映新状态,调用方无需(也不应)再自行调 `reload()`。
- `SharedMemoryLoader` 容错:目录中放一个不可读 / 内容异常的文件,`load` / `modify` 不抛异常,该文件不进快照也不被删除。
- `SharedMemoryLoader.of`:同一目录两次调用返回**同一实例**(快照与锁共享);`./mem` 与绝对路径归一到同一 key;同一目录传不同 `maxFileCount` 抛 IllegalArgumentException;不同目录互不影响。(测试用 `@TempDir` 保证目录唯一,避免全局注册表串场)
- `SharedMemoryLoader.create`:可注入极小条数上限与内存 store,不碰磁盘;`create` 出的实例与注册表无关。
- `SharedMemorySaveTool.execute`:
  - append 新增一行 / update 整行替换与删除(空串删行,不留空行)
  - 非法 name(`../x`、含 `/`、超长)返回 isError,不落盘
  - 新记忆创建 → loader 内部已 reload,`loader.get` 立即可命中
  - 已有记忆更新 lastAccessedAt
  - 超过 maxFileCharSize(字符) → 触发同步压缩(mock LlmProvider)
  - 回执不含字符数;压缩成功时带 "compressed" 说明,压缩失败但超限时带 WARNING
  - update 的 old_text 为空串 / 全空白 → 返回 error 且不落盘
  - update 的 old_text 只引用了半行 / 漏了标点 → 返回 error,不落盘(条目必须整行匹配)
  - update 的 old_text 与多行都相同 → 只替换最上面那一行,其余不动
  - update 的 content 为空串 → 删除该行且不留空行,其余行顺序不变;回执是 "Removed one entry from ...",不能写成 Saved
  - update 缺 content(键缺失)→ 返回 error 且不落盘(漏传字段不得被当成删除指令)
  - append 传空白 content → 返回 error 且不落盘(不得向正文里塞空行)
  - 新建时 description 超长 → 截断到 `MAX_DESCRIPTION_CHARS`
  - old_text / content 含 `$`、`\`、`.`、`(` → 按字面量逐行比较,不做正则解析
  - newBody 超过 `maxFileCharSize × 4` 且这次把它撑大 → 返回 error 且不落盘(压缩输入都装不下)
  - 已经超 `maxFileCharSize × 4` 的记忆被 update 改短 → 放行并落盘(否则它永久不可修)
  - save 后条数超额 → 淘汰其他最久未访问的(loader.modify 内部 reload 生效)
  - `onUserProfileUpdated`:name==`user` 且写入成功时被调用一次,失败路径(notFound / missingDescription / tooLarge)不调;其他 name 不调;传 null 时一切照旧。
- `SharedMemoryPlugin.initialize`:user.md 不存在时创建,已存在时不覆盖;正文为空时 install 不注入段落。
- `SharedMemoryPlugin` 运行期刷新:save("user") 成功后 persona 文本立即更新(v1 → v2),且 `<user-profile>` 只出现一次(原位替换而非追加);save 其他记忆不改变 persona;触发压缩时刷新用的是压缩后正文而非入参 content。
- `SharedMemoryLoadTool.execute`:保留名 `user` 返回错误(即使 `user.md` 在磁盘上存在也不返回正文);不存在的 name 返回 not found。

### 7.2 集成测试

- 用 mock `LlmProvider`,模拟 LLM 调用 `save_shared_memory("travel", "...")` → `load_shared_memory("travel")` → 返回写入内容。
- 模拟压缩:`maxFileCharSize=100`(字符),写入 200 字符内容,验证压缩被触发且 md 最终被覆盖为压缩后内容。再补一条:同为 200 字符但全为中文时仍触发(证明按字符而非字节判定)。
- 模拟淘汰:用 `SharedMemoryLoader.create(store, maxFileCount = 2)` 注入小上限,建 A/B 两条间隔写入的记忆,再写入 C——验证最久未访问的 A 先被删,B/C 保留;再把上限设为 1 写入 D,验证只留最新的一条,且不会删空。
- 缺省情况下(条数未超额)多次 load/save 不删任何文件——确保没有残留的时间遗忘。

### 7.3 不在测试范围

- 真实 LLM 调用(用 mock)。
- Android 平台适配(用 Path 在 JVM 测试)。
- 与 Skill/Subagent 共存的兼容性(共享记忆是独立 Tool,不依赖 Capability 框架,共存由 ToolRegistry 自然保证)。

---

## 8. 已知限制与未来工作

### 8.1 当前限制

1. **跨进程并发不支持**:per-name Mutex 只在单进程内生效,按目录的 loader 单例也只是**进程内**共享。两个进程挂同一 `directory` 时,既可能 lost update,也互看不见对方新建的记忆。宿主跨进程共享须自行加协调层。
2. **淘汰无后台巡检**:条数统计与淘汰只随 loader 的 `load` / `modify` 内部 `reload()` 发生。宿主长时间不调工具时,目录可能一直超出条数(下次读写或下次 install 时才收敛)。需要主动巡检时,得先开放一个公开入口(`reload()` 目前是 private)。
3. **存储实现不可替换**:`SharedMemoryStore` 是 `internal`,宿主只能走 `PathSharedMemoryStore`。由于依赖 `java.nio.file`,**Android API < 26 不可用**。需要平台适配时,把接口提为 `public` 并在 DSL 加 `store` 参数即可(注入缝已预留)。
4. **记忆文件名只认 ASCII**:符合 `[A-Za-z0-9._-]` 且 ≤ 64。目录里其他 `.md`(中文名、带空格)会被 `list()` 忽略——既不进快照也不占名额,算是"安全地看不见"。
5. **条数上限只能首次设定**:同一目录的 loader 共享,所以 `maxFileCount` 只由首次注册决定;后续对同一目录传不同值会直接抛 IllegalArgumentException。需要按场景调整时,得先保证该目录只有一个"归属方"。
6. **目录注册表常驻不回收**:`SharedMemoryLoader.instances` 是进程级 `ConcurrentHashMap`,`of()` 不回收入口。宿主不断换目录(如每次请求一个临时目录)会缓慢累积——量级上应当"每用户/每 agent 一个稳定目录",v1 不做引用计数。
7. **user 段落只能整体替换**:`AgentPluginContext` 没有 remove 段落的能力,所以 user 正文被清空时会留下一个空段落(`<user-profile></user-profile>`);若将来确实需要"彻底移除",得先在 core 补一个 `removeInstruction(label)`。
8. **`maxFileCharSize` 是软上限**:压缩失败(LLM 报错 / 摘要没变短)时超长正文照存,读路径不做拦截也不截断。真正保证"单条装得下"的只有 `save` 时的压缩这一次尝试。
9. **记忆文件不支持人工维护**:frontmatter 由 `render` 全量生成,`load` 也会写回(刷新 `lastAccessedAt`),所以手工添加的字段 / 注释 / 换行风格都会在下一次读写时被规范化。`load` 引起的 mtime 变化是**预期行为**——访问时间存在文件里,不写回就记不住;只有把目录纳入 git / 云同步 / 增量备份的宿主需要知道这一点。删文件是允许的(下次 reload 就消失),改内容不是。
10. **per-name 锁表按 name 累积、不回收**:`PathSharedMemoryStore.locks` 为每个出现过的 name 留一个 `Mutex`,记忆被淘汰时不清除。不能在淘汰时 `remove`:它与 `computeIfAbsent` 之间没有原子性,"刚拿到的锁被移除、第三方又造一把"会让同名并发写重新变得可能——宁可留一个对象也不能丢互斥。量级上可忽略:每条目约 100 字节(含 map 节点),按每天新增 100 个不同名字、持续一年算也只有几 MB,而宿主进程通常活不到这个量级。若将来确需收敛,正确形态是固定分段锁(按 `name.hashCode()` 经 `Math.floorMod` 取模,段数按同时在途的操作数而非记忆总数决定),而不是淘汰时回收;且必须是可挂起的 `Mutex`,不能用 `Any` + `synchronized`——压缩在锁内挂起数秒,阻塞线程会耗尽调度线程池。
11. **Windows 上约 1% 的 rename 会被拒,但不会丢内容**:`PathSharedMemoryStore.modify` 用"写 `name.md.tmp` → `ATOMIC_MOVE` 覆盖目标"落盘,NTFS 的 rename 要求目标可删除,杀软 / 索引器短暂持有句柄时会抛 `AccessDeniedException`。本机实测同一文件连续重写 2000 次命中 23 次(约 1%),且与调用间隔无关(0 / 10 / 100 ms 间隔都出现过),所以不是"高频才触发"而是每次操作都带固定小概率;POSIX 的 `rename(2)` 不申请目标文件的 delete 权限,Linux / macOS 不出现;Android 上概率低但存在(媒体扫描器)。失败语义是**整体不生效**,不是丢失或损坏:rename 失败等于目录项指针没改,目标文件仍是上一次成功写入的**完整**版本,本次新内容完整留在 `.md.tmp`(单次 `write` 已成功),`ATOMIC_MOVE` 也保证不会出现半截文件。代价只有两条:该次 `load` / `save` 返回 error(`save` 失败时 `onUserProfileUpdated` 不会被调用,persona 与磁盘仍一致;模型或调用方重试一次即成功);`load` 失败时 `lastAccessedAt` 未刷新,该记忆在淘汰队列里保持原位、看起来比实际更"冷"。`.md.tmp` 不在 `list()` 结果内(按扩展名 `md` 过滤),既不占名额也不会被读,下次同名写入直接覆盖回收。不加重试是有意取舍:数据零风险,代价只是"偶发一次工具调用失败 + 一个几十字节的垃圾文件",而重试要给 Store 引入平台相关分支和一条需要构造句柄占用的用例。若将来要消除,正确做法是在 `move` 处捕获 `AccessDeniedException` 做退避重试(实测退避 20ms 重试一次即可 100% 成功),而不是放弃 `ATOMIC_MOVE` 退化成非原子覆盖。
12. **已有记忆的 description 只能靠改文件**:`save_shared_memory` 对已存在的记忆忽略 `description` 入参,`load_shared_memory` 又只读不写,所以模型把描述写歪后没有工具能修——只能由宿主改 frontmatter(下一次读写按规范格式重写)。之所以不放开"update 时顺带改 description",是因为那会给"选中已有记忆"这条最高频路径多开一个能覆盖元数据的入口;长度失控已由 `MAX_DESCRIPTION_CHARS`(新建与解析两处截断)兜住。需要修正时再补一个显式模式(如 `mode=describe`)。

### 8.2 未来扩展

1. **手动巡检/整理**:开放一个公开入口(把 `reload()` 提为 public 或加 `purge()` / `compact()`),由宿主主动触发淘汰或压缩。
2. **记忆合并**:多个相关 md 合并为一个,需要 LLM 识别"相关"。
3. **淘汰回报给 LLM**:`save_shared_memory` 的返回里带上本次被淘汰的记忆名,让模型知道哪些信息丢了,可及时重写。
4. **淘汰规则改成 LLM 决策**:不再只按 lastAccessedAt,而是把清单交给模型选"该删哪条、该合并哪几条"——按时间淘汰依旧分不清重要与不重要。
5. **跨进程快照失效感知**:多进程共享同一 `directory` 时,A 进程 save 后 B 进程的 loader 快照仍是旧的。未来可加 FS watch 或 polling reload,使 B 进程自动感知新记忆。v1 不处理。

---

## 9. 实现顺序建议

1. 在 `settings.gradle.kts` 加 `include(":agent:memory:shared")`,建模块(模板同 `agent/skill`,`explicitApi()`,不引 serialization 插件)。
2. 实现 `SharedMemoryStore` / `SharedMemory`(纯值对象,含 frontmatter 解析与渲染)/ `SharedMemoryLoader`(含按目录单例 `of()`、条数淘汰与 `load` / `modify` 入口)。
3. 实现 `SharedMemoryLoadTool`(读出路径)。
4. 实现 `SharedMemorySaveTool`(不含压缩,先跑通读写路径)。
5. 实现 `SharedMemoryPlugin` + `AgentBuilder.sharedMemory(...)`,接入 AgentBuilder。
6. 加同步压缩逻辑(并入 save 流程,与 write 同锁)。
7. 实现 reload 的条数淘汰(条数统计 + 最久未访问优先),并将它收进 `load` / `modify` 内部自动调用(工具与插件不显式调)。
8. 加 user.md 初始化 + `addInstruction` 注入,并把"save(user) 成功后原位刷新该段落"的回调接到 save 工具上。
9. 单元测试 + 集成测试。
10. demos/agent 加一个共享记忆示例。

---

## 10. 相关文档

- `docs/superpowers/specs/2026-08-11-capability-installer-design.md` — CapabilityPlugin 抽象类设计
- `docs/memory-management.md` — core Memory(短期会话记忆)的设计
- `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/Skill.kt` — Skill 接口,本插件的对称参考
