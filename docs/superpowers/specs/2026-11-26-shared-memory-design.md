# 共享记忆插件设计

> 日期：2026-11-26 · 状态：**Proposed**
> 模块：`agent/memory/shared`（新增）
> 范围：新增一个独立的共享记忆插件,与 core 中的短期会话 Memory 正交;LLM 通过 `load_shared_memory` / `save_shared_memory` 两个工具读写 markdown 记忆文件,每轮主动注入 `user.md` 用户画像到 system prompt,并提供遗忘与压缩机制。

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
4. 共享记忆需要生命周期管理:超过一定时间没被读写则遗忘;单文件过大则压缩。
5. 存储位置由宿主决定(必传 `directory`),插件不做平台推断。

与现有 `Memory` 的区别:
- `Memory` 是 LLM 线格式消息的历史(`MemoryEntry`),按轮次/字节压缩,生命周期是单次会话。
- 共享记忆是 markdown 文件,按记忆组织,按时间/大小做生命周期,生命周期跨会话。
- 两者正交,互不依赖。

---

## 2. 设计原则

- **独立实现,不依赖 capability 框架**:共享记忆是内部生成的(由 `SharedMemoryLoader` 扫描目录构建),不需要外部宿主或插件提供 `Capability` 实例,与 Skill 的"外部注册"形态本质不同。`SharedMemoryPlugin` 直接实现 `AgentPlugin<SharedMemoryPluginConfig>`(`SharedMemoryPluginConfig` 是 object 占位,满足 `C : Any` 约束),`load_shared_memory` / `save_shared_memory` 两个工具都是普通 `Tool`,在 `install` 里手工 `registerTool`。不继承 `CapabilityPlugin`、不调 `CapabilityAdapter`、不依赖 `CapabilityLoadTool`。
- **公开面最小**:对外只暴露一个扩展函数 `AgentBuilder.sharedMemory(...)`。`SharedMemory` / `SharedMemoryLoader` / `SharedMemoryStore` / `PathSharedMemoryStore` / 两个 Tool / `SharedMemoryPlugin` / `SharedMemoryPluginConfig` 全部 `internal`——它们是同一模块内的实现细节,宿主不需要也不应该感知。将来要开放(如平台存储适配)再逐个提权。
- **写入决策在 LLM**:不暴露写入 API 给宿主代码,只有 `save_shared_memory` 工具。宿主只负责传 `directory` 与可选的 `memoryFileLimit` / `forgetDuration` / `LlmProvider`。
- **存储抽象最小**:`SharedMemoryStore` 是模块内部的可替换缝(单测可换内存实现),v1 只有 `PathSharedMemoryStore` 一个实现,不对外暴露。不预先做 Android/iOS 平台适配。
- **分层清晰**:`SharedMemory` 是纯值对象(元数据 + md 编解码),不持 store、无 IO;`SharedMemoryStore` 只管文本存取;`SharedMemoryLoader` 独占持有 store,负责扫描、过期判定与读写入口(锁内读 + touch、`modify`)。依赖方向只有 `Loader → SharedMemory / Store`。
- **过期清理只发生在 reload**:遗忘的唯一判定点是 `SharedMemoryLoader.reload()`。loader 的读写入口(`load` / `modify`)完成操作后各自自动调一次 `reload()`,删除过期文件并重建"只含可读记忆"的快照——工具与插件都不感知。读写路径自身不判过期——本次操作的记忆 `lastAccessedAt` 刚被刷新,天然不会被删,要清理的是**其他**长期未访问的记忆。压缩仍并入 `save_shared_memory` 流程同步触发。
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
    │   ├── SharedMemory.kt          # internal class SharedMemory(纯值对象:元数据 + md 编解码,无 IO)
    │   ├── SharedMemoryPlugin.kt    # internal class SharedMemoryPlugin : AgentPlugin<SharedMemoryPluginConfig>
    │   ├── SharedMemoryLoader.kt    # internal class:扫描/过期清理/读写入口(load / modify,内部自动 reload)
    │   ├── SharedMemoryStore.kt     # internal interface SharedMemoryStore + internal PathSharedMemoryStore
    │   ├── SharedMemoryLoadTool.kt  # internal class,工具 load_shared_memory
    │   ├── SharedMemorySaveTool.kt  # internal class,工具 save_shared_memory(含同步压缩)
    │   └── SharedMemoryExtensions.kt # public fun AgentBuilder.sharedMemory(...) —— 唯一的公开入口
    └── test/kotlin/io/github/yeyi/agent/memory/shared/
        └── (各组件单元测试)
```

依赖关系(全部**不**向上游泄漏:模块对外 API 只有 `Path` / `Duration` / `LlmProvider`):
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

import java.time.Duration
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
 * - 过期判断只由 `SharedMemoryLoader.reload` 通过 [isExpired] 做
 */
internal class SharedMemory(
    /** 记忆名(等于文件名,不含 .md)。解析时以文件名为准。 */
    val name: String,
    /** 给 LLM 看的"何时使用本记忆"提示。 */
    val description: String,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 最后一次被 load/save 的时间,遗忘判断的依据。 */
    val lastAccessedAt: Instant,
) {
    /** 是否超过 forgetDuration 未被读写。只由 `SharedMemoryLoader.reload` 使用。 */
    fun isExpired(now: Instant, forgetDuration: Duration): Boolean =
        Duration.between(lastAccessedAt, now) > forgetDuration

    /** 拷贝一份新实体,并把访问时间更新为 [lastAccessedAt](实体不可变)。 */
    fun copyWith(lastAccessedAt: Instant): SharedMemory =
        SharedMemory(name, description, createdAt, lastAccessedAt)

    /**
     * 用本元数据 + [body] 渲染 md 全文,写回文件时使用。
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
         * 纯函数:解析 md 全文的 frontmatter 构建元数据。[name] 取文件名(不含 .md),
         * 与 frontmatter 中的 name 冲突时以文件名为准。不抛异常。
         *
         * 缺字段兜底原则是"宁可永不过期,也不能误删":
         * - 缺 `createdAt` → 用 `lastAccessedAt`;缺 `lastAccessedAt` → 用 `createdAt`
         * - 两者都缺或时间戳无法解析 → 用解析时刻 now(而不是 EPOCH,否则下次 reload 直接删掉)
         * - 缺 `description` → 空串
         * - 没有 frontmatter(整段都是正文)→ 全部兜底,全文作正文
         */
        fun parse(name: String, content: String): SharedMemory { /* 手写 frontmatter,非 YAML 库 */ }

        /** 取正文(去掉 frontmatter 后的部分);没有 frontmatter 时返回全文。 */
        fun bodyOf(content: String): String { /* frontmatter 之后的文本 */ }

        /** 新建一个记忆(user.md 初始化用)。 */
        fun create(name: String, description: String, now: Instant): SharedMemory =
            SharedMemory(name, description, createdAt = now, lastAccessedAt = now)
    }
}
```

### 4.2 `SharedMemoryLoader` —— 扫描、过期清理与快照

```kotlin
package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/**
 * 共享记忆加载器,负责扫描目录、清理过期记忆、维护 name → SharedMemory 快照。
 *
 * 与 CapabilityRegistry 不同:这里没有外部注册者,清单完全由目录扫描得到,
 * 因此不提供 register/remove,对外只有 [load] 读取 + [modify] 写入 + 只读查找;
 * 重建快照是与它们绑定的内部动作,不单独对外暴露。
 *
 * **internal**:宿主拿不到本类,也就不存在"忘记调 reload"或"调错顺序"的问题。
 *
 * - [reload] 是遗忘的唯一判定点:扫描根目录所有 `.md` 文件(排除 `user.md`),
 *   删除过期文件,再用剩下的"可读"记忆整体替换快照。
 * - 因此快照恒为可读集合:凡 [all] / [get] 返回的记忆一定未过期。
 * - [load] 是读出路径:在 per-name 锁内取正文并刷新访问时间,不判过期,完成后自动 [reload]。
 * - [modify] 是写入入口:插件与 save 工具都经此访问存储,完成后同样自动 [reload]。
 * - 快照只由本类维护,所以"重建快照"收在 [load] / [modify] 内部,调用方不可能漏掉。
 * - 已删除的文件从清单移除;新增的文件加入清单;变更的 frontmatter 反映到 description。
 * - 容错:单个文件读失败或内容损坏 → 跳过该项(不进快照、也不删),reload 不因单文件问题整体失败。
 * - 线程安全:reload 在 [mutex] 内重建不可变快照,查找直接读快照,无需加锁。
 */
internal class SharedMemoryLoader(
    private val memoryStore: SharedMemoryStore,
    private val forgetDuration: Duration,
) {
    private val mutex = Mutex()

    /** 不可变快照;reload 整体替换,读方无锁。快照中只含未过期的记忆。 */
    @Volatile
    private var snapshot: Map<String, SharedMemory> = emptyMap()

    /** 当前可读的所有记忆(快照)。 */
    fun all(): List<SharedMemory> = snapshot.values.toList()

    /** 按 name 查找记忆(目前用于测试断言与 description 渲染)。 */
    fun get(name: String): SharedMemory? = snapshot[name]

    /**
     * 读取一条记忆的正文,并把 lastAccessedAt 刷新为 now。
     *
     * 只读:不判过期(判定只在 [reload],因此已超阈值的记忆也照样能读出)。
     * 正文在 per-name 锁内读得,即"读"那一刻磁盘上的内容,不会与并发 save 错位;
     * 刚被刷新过的 lastAccessedAt 也使随后的 [reload] 不会把它误删。
     * 完成后自动 [reload]:遗忘了其他长期未访问的记忆。
     *
     * @return 正文(不含 frontmatter);文件不存在(已被遗忘)时返回 null
     */
    suspend fun load(name: String): String? {
        var body: String? = null
        memoryStore.modify(name) { current ->
            if (current == null) return@modify null
            val text = SharedMemory.bodyOf(current)
            body = text
            SharedMemory.parse(name, current)
                .copyWith(lastAccessedAt = Instant.now())
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
     * 实现约束:[reload] 会对过期候选再进一次 per-name 锁,嵌套会自锁——
     * 所以 reload 只能放在 store.modify 返回之后,不能放进 transform 里。
     */
    internal suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? {
        val result = memoryStore.modify(name, transform)
        reload()  // 本次写入的记忆 lastAccessedAt 刚刷新,不会被删
        return result
    }

    /**
     * 重扫根目录:删除过期记忆,重建快照。
     *
     * 由 [load] / [modify] 在操作完成后自动调用,不对外暴露:快照只由本类维护,
     * 外部无法也不能"只重建快照而不做读写",也就无法造成快照与磁盘脱节。
     * 若将来需要宿主主动巡检,再单独开一个公开的巡检入口。
     * 当前操作的记忆 lastAccessedAt 刚被刷新,不会命中过期判定——被遗忘的是**其他**记忆。
     *
     * 调用约束:不得在任何 per-name 锁内调用(会自锁)。
     */
    private suspend fun reload(): Unit = mutex.withLock {
        val now = Instant.now()
        val next = LinkedHashMap<String, SharedMemory>()

        // list() 返回所有 .md 文件名(不含 user.md),即遗忘的候选集
        for (name in memoryStore.list()) {
            // 单文件读失败或正被并发删除:跳过该文件,不中断整轮,也不删除它(不毁用户数据)
            val content = runCatching { memoryStore.read(name) }.getOrNull() ?: continue
            val memory = SharedMemory.parse(name, content)
            if (!memory.isExpired(now, forgetDuration)) {
                next[name] = memory
                continue
            }

            // 疑似过期:进 per-name 锁内重新判定后再删,避免误删刚被并发 save/load 刷新过的记忆
            val kept = memoryStore.modify(name) { current ->
                val latest = current?.let { SharedMemory.parse(name, it) }
                    ?: return@modify null
                if (latest.isExpired(Instant.now(), forgetDuration)) null else current
            } ?: continue
            next[name] = SharedMemory.parse(name, kept)
        }

        snapshot = next
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
 * 2. context.addInstruction(userBody, "user-profile") 注入 `user.md` 正文到 persona 尾部独立段落
 * 3. registerTool(SharedMemoryLoadTool(loader))  → load_shared_memory
 * 4. registerTool(SharedMemorySaveTool(loader, memoryFileLimit, llmProvider)) → save_shared_memory
 *
 * 插件不持有 [SharedMemoryStore]、也不调 reload():存储与快照都由 [SharedMemoryLoader] 独占维护,
 * 读写入口内部自动重建快照。
 */
internal class SharedMemoryPlugin(
    private val loader: SharedMemoryLoader,
    private val memoryFileLimit: Int,
    private val llmProvider: LlmProvider?,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) { /* no-op,配置已在构造器完成 */ }

    override fun install(context: AgentPluginContext) {
        val userBody = runBlocking { initialize() }  // ensureUserMemory;内部 loader.modify 已自动 reload

        // 注入 user.md 正文到 persona 尾部独立段落(具名 label,便于后续原位刷新)
        if (userBody.isNotBlank()) {
            context.addInstruction(userBody, "user-profile")
        }

        // 注册两个工具(普通 Tool,不经过 CapabilityAdapter)
        context.registerTool(SharedMemoryLoadTool(loader))
        context.registerTool(
            SharedMemorySaveTool(loader, memoryFileLimit, llmProvider)
        )
    }

    // 不再显式调 reload():loader.modify 内部已完成快照重建 + 遗忘清理
    private suspend fun initialize(): String = ensureUserMemory()

    // 若 user.md 不存在,创建空模板;返回 user.md 正文(供 install 注入)
    private suspend fun ensureUserMemory(): String {
        val empty = SharedMemory.create(
            name = SharedMemory.RESERVED_USER,
            description = "用户画像,每轮主动注入 system prompt",
            now = Instant.now(),
        )
        // 已存在时 modify 会原样重写一次(原子替换),成本可接受;顺带的 reload 完成 install 首次遗忘清理
        val content = loader.modify(SharedMemory.RESERVED_USER) { current ->
            current ?: empty.render(body = "")
        } ?: return ""
        return SharedMemory.bodyOf(content)
    }
}
```

**注意 `runBlocking` 的使用**:`install` 是同步签名(`AgentPlugin.install` 非 suspend),但 `SharedMemoryStore` 的操作是 suspend。`runBlocking` 在 install 阶段阻塞直到完成,这是插件初始化的合理代价——install 在 Agent 构建期执行,不在 LLM 推理路径上。

### 4.4 `SharedMemoryLoadTool` —— 读出工具

普通 `Tool`,不继承 `CapabilityLoadTool`。description 每轮求值(用 getter 而非 `by lazy`),拼出当前 loader 快照中所有记忆的 name + description 列表,使 reload 后下一轮 LLM 自动看到新记忆。execute 只调一句 `loader.load(name)`(锁内读正文 + 刷新访问时间,不判过期;完成后 loader 内部自动 reload,遗忘的是其他过期记忆)。

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
            if (loader.all().isEmpty()) {
                appendLine("(暂无)")
            } else {
                loader.all().forEach { m ->
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

        // 锁内读正文 + 刷新 lastAccessedAt,不判过期;loader 随后自动 reload
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
 * LLM 写入共享记忆的工具。
 *
 * 参数:
 * - name: 记忆名(对应文件名,不含 .md)。字符合集 `[A-Za-z0-9._-]`、≤ 64,与 store 的校验一致
 *   (不校验就会变成路径穿越:`../../x` 写出目录之外)
 * - description: 记忆描述(给 LLM 看的"何时使用本记忆"提示)。**新建记忆时必填**;已有记忆忽略此参数
 * - content: 写入内容(markdown)
 * - mode: "append" 追加到正文末尾 / "update" 局部替换(配 old_text,content 作替换文本)
 *
 * 副作用:
 * - 更新 frontmatter.lastAccessedAt(= now):写入即访问
 * - 写入后 loader.modify 内部自动 reload:重建快照(新记忆由此进入清单),并遗忘其他过期记忆
 *   (本次写入的记忆 lastAccessedAt 刚刷新,不会被删;user.md 不在 list() 中,天然豁免)
 * - 写入后若 newBody 超过 memoryFileLimit,同步调 LLM 压缩并覆盖写回(与 write 同一把 per-name 锁,无竞态窗口)
 *
 * **不做过期检查**:写入即视为访问,已过期的记忆会被"复活"(追加/替换旧正文并刷新 lastAccessedAt),
 * 而不是被遗忘。遗忘只发生在随后的 reload 里,且针对的是其他记忆。
 *
 * 返回:写入确认信息(包含字符数、是否触发压缩)
 *
 * 并发语义:本工具自身不持锁,串行性由 [SharedMemoryStore] 的 per-name 互斥保证——
 * save 与 load、save 与压缩对同一 name 自动串行。存储经 [SharedMemoryLoader.modify] 访问。
 */
internal class SharedMemorySaveTool(
    private val loader: SharedMemoryLoader,
    private val memoryFileLimit: Int,
    private val llmProvider: LlmProvider?,
) : Tool {
    override val name: String = "save_shared_memory"

    override val description: String = """
        将信息写入共享记忆文件。当用户要求或你发现了值得跨会话长期记住的事实(偏好、背景、约定等)时调用本工具。

        调用前请先看 load_shared_memory 工具描述里列出的（已存在）记忆文件列表,选择要记录的内容归属于哪个记忆文件:
        - 有合适的记忆文件:传该记忆文件的 name,description 留空(记忆文件已存在，不需要描述)
        - 没有合适的记忆文件:传新 name + 必填的 description(一句话告诉未来的自己何时该加载本记忆文件)，由程序新建

        参数:
        - name: 记忆文件名(英文短词,如 "travel-preferences")。
        - description: 记忆文件描述。新建记忆文件时必填,选中合适的记忆文件时忽略。例:"用户旅行偏好与历史行程"
        - content: 记忆内容。append 模式下追加到现有正文末尾;update 模式下作为替换后的新片段(空串表示删除该片段)。
        - mode: "append" 或 "update",默认 "append"。
          - append: 追加 content 到现有正文末尾。记忆文件新建时也走此模式(此时正文为空)。
          - update: 局部替换。需配 old_text,工具读旧正文找到 old_text 替换为 content。
                    content 为空串表示删除该片段。找不到 old_text 则返回错误,不修改文件。
        - old_text: 仅 update 模式必填。要被替换的原文片段,必须与现有正文中的文本精确匹配。
    """.trimIndent()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","update"]},"old_text":{"type":"string"}},"required":["name","content"]}"""
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
            ?: if (mode == "append") return ToolExecutionResult.error("Missing 'content'") else ""
        val oldText = arguments.jsonObject["old_text"]?.jsonPrimitive?.content
            ?: if (mode == "update") return ToolExecutionResult.error("Missing 'old_text'") else null

        val llmProvider = llmProvider ?: context.agentContext.llmProvider
        val now = Instant.now()

        // 状态变量:由 transform 内部写入,execute 外层读取以决定返回值
        var notFound = false            // update 模式 old_text 找不到 / update 不存在 / 非法 mode
        var missingDescription = false  // 新建记忆但 description 为空
        var finalLen = 0                // 最终正文长度
        var compressed = false          // 是否触发压缩

        loader.modify(name) { current ->
            val meta = if (current == null) {
                if (description.isNullOrBlank()) { missingDescription = true; return@modify null }
                SharedMemory.create(name, description, now)
            } else {
                // 已有记忆:description / createdAt 沿用旧值,忽略入参
                SharedMemory.parse(name, current)
            }
            val body = current?.let { SharedMemory.bodyOf(it) } ?: ""

            val newBody = when (mode) {
                "append" -> if (body.isBlank()) content else "$body\n\n$content"
                "update" -> {
                    if (current == null) { notFound = true; return@modify null }
                    if (!body.contains(oldText!!)) { notFound = true; return@modify null }
                    body.replace(oldText, content)
                }
                else -> { notFound = true; return@modify null }
            }

            val finalBody = if (newBody.length > memoryFileLimit) {
                compressMemory(llmProvider, memoryFileLimit, newBody)?.also { compressed = it.length < newBody.length } ?: newBody
            } else {
                newBody
            }
            finalLen = finalBody.length
            meta.copyWith(lastAccessedAt = now).render(finalBody)  // 写入即访问
        }

        when {
            missingDescription -> return ToolExecutionResult.error(
                "Missing 'description': required when creating a new memory '$name'"
            )
            notFound -> return ToolExecutionResult.error(
                if (mode == "update") "old_text not found or memory '$name' does not exist" else "Invalid mode: $mode"
            )
        }

        return ToolExecutionResult.success(
            "Saved memory '$name' ($finalLen chars).${if (compressed) " Compressed." else ""}"
        )
    }

    private suspend fun compressMemory(
        llmProvider: LlmProvider,
        memoryFileLimit: Int,
        body: String,
    ): String? {
        val targetSize = memoryFileLimit / 2
        val prompt = """
            请整理以下共享记忆内容,按以下规则压缩:
            1. 去重:相似信息只保留最后一份，但不是简单的裁切，而是语义融合
            2. 去冲突:语义相反信息以最后一份为准，去掉前面冲突的内容
            3. 合并:相关信息合并为更紧凑的表述(如"喜欢海鲜"+"喜欢日料"→"喜欢海鲜与日料")
            4. 保留:所有关键事实必须保留,不能因压缩丢失语义，且保持原有的记忆条目的格式与顺序

            只输出压缩后的正文,不要任何前缀说明。压缩到 $targetSize 字符以内。

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
        // 无效兜底:压缩结果没有变短,说明压缩失败,返回 null 让调用方保留原文
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
    suspend fun list(): List<String> // 所有 .md 文件名(不含 .md 后缀),排除 RESERVED_USER;即 reload 的遗忘候选集
}

/**
 * 基于 [Path] 的 [SharedMemoryStore] 默认实现。
 *
 * - 文件名:`$directory/$name.md`,`name` 先过 [MEMORY_NAME_PATTERN] 校验
 * - IO 走 [Dispatchers.IO]
 * - `user.md` 文件存在但不列入 list()(避免被 loader 当作普通记忆加载、遗忘)
 * - per-name 互斥:[locks] 为每个 name 维护一把 [Mutex],[modify] 全程持锁,
 *   使 save 与压缩、save 与 load 对同一 name 串行
 * - write 走原子写入:先写 `.tmp` 再 [Files.move] 带 [StandardCopyOption.ATOMIC_MOVE] 替换目标,
 *   避免写一半中断导致文件损坏(残留的 `.tmp` 不会被 list() 当成记忆,也不影响读取)
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
        withContext(Dispatchers.IO) {
            if (result == null) {
                target.delete()
            } else {
                // 原子写入:先写 .tmp,再 ATOMIC_MOVE 替换目标,避免写一半中断导致文件损坏
                val tmp = directory.resolve("$name.md.tmp")
                Files.write(tmp.toPath(), result.toByteArray(Charsets.UTF_8))
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
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
        directory.toFile().listFiles { f -> f.extension == "md" && f.nameWithoutExtension != SharedMemory.RESERVED_USER }
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
import java.time.Duration

/**
 * 注册共享记忆插件到 Agent —— **本模块唯一的公开 API**。
 *
 * 与 core 的 `AgentBuilder.memory(Memory, maxRounds)`(短期会话历史)区分开,所以不叫 `memory(...)`:
 * `sharedMemory` 是跨会话的 markdown 记忆,两者正交。
 *
 * @param directory 记忆文件根目录,宿主必传(不存在会自动创建)
 * @param memoryFileLimit 单文件字符上限,超则同步压缩。默认 8000,必须 > 0
 * @param forgetDuration 记忆超过此时长未被读写则被遗忘删除。默认 30 天,必须 > 0
 * @param llmProvider 压缩用 LLM;null 时回退到 AgentContext.llmProvider。默认 null
 */
public fun AgentBuilder.sharedMemory(
    directory: Path,
    memoryFileLimit: Int = 8000,
    forgetDuration: Duration = Duration.ofDays(30),
    llmProvider: LlmProvider? = null,
) {
    require(memoryFileLimit > 0) { "memoryFileLimit must be positive: $memoryFileLimit" }
    require(forgetDuration > Duration.ZERO) { "forgetDuration must be positive: $forgetDuration" }
    plugin(
        SharedMemoryPlugin(
            loader = SharedMemoryLoader(PathSharedMemoryStore(directory), forgetDuration),
            memoryFileLimit = memoryFileLimit,
            llmProvider = llmProvider,
        )
    )
}
```

---

## 5. 数据流

### 5.1 install 阶段

```
AgentBuilder.sharedMemory(directory, memoryFileLimit, forgetDuration, llmProvider)
  → require 校验参数(memoryFileLimit / forgetDuration 必须 > 0)
  → plugin(SharedMemoryPlugin(loader = SharedMemoryLoader(PathSharedMemoryStore(directory), forgetDuration), ...))
    → SharedMemoryPlugin.install(context)
      → runBlocking { initialize() }
        → ensureUserMemory()             # 经 loader.modify(RESERVED_USER) 建空模板;完成后 loader 内部已自动 reload
      → context.addInstruction(userBody, "user-profile") # 注入到 persona 尾部独立段落
      → context.registerTool(SharedMemoryLoadTool(loader))                    # load_shared_memory
      → context.registerTool(SharedMemorySaveTool(loader, memoryFileLimit, llmProvider)) # save_shared_memory
```

### 5.2 每轮 LLM 请求

```
ReActAgent 构造 ChatRequest
  → ToolRegistry.all().map { it.toDefinition() }
    → load_shared_memory.toDefinition()  # 读 description,触发 getter → 读 loader.all() → 列出所有记忆名+描述
    → save_shared_memory.toDefinition()
  → ChatRequest.tools = [load_shared_memory, save_shared_memory, ...其他工具]
  → system prompt 中含 user.md 内容(通过 addInstruction 注入 persona 尾部独立段落)
  → LLM 看到所有记忆名 + user 画像
```

### 5.3 LLM 调用 load_shared_memory(name="travel")

```
load_shared_memory.execute({name: "travel"})
  → loader.load("travel")
      → memoryStore.modify("travel") { current ->        # per-name 锁内
          text = bodyOf(current)                          # 锁内读到的最新正文,即本次返回值
          parse(current).copyWith(lastAccessedAt = now).render(text)   # 只刷新访问时间,不判过期
        }
      → 返回 text;文件不存在(已遗忘)时返回 null
  → loader.load 内部自动 reload()                          # 遗忘:删除其他过期记忆 + 重建快照
  → ToolExecutionResult.success(正文 / error "not found")
```

### 5.4 LLM 调用 save_shared_memory(name, content, mode)

```
save_shared_memory.execute({name, content, mode, old_text?})
  → 解析参数
  → loader.modify(name) { current ->                     # per-name 锁内,整流程原子(经 loader 委托到 store)
      current == null → SharedMemory.create(name, description, now),body = ""
      current != null → parse,description / createdAt 沿用旧值
      mode=append: body + "\n\n" + content
      mode=update:
        current == null → notFound 标记,返回 null
        body.contains(old_text) 失败 → notFound 标记,返回 null
        body.replace(old_text, content)   # content="" 表示删除
      不判过期:lastAccessedAt = now(写入即访问,已过期记忆被复活)
      若 newBody.length > memoryFileLimit:
        → 同步调 compressMemory(newBody)   # 仍在 modify 锁内
        → 压缩成功(summary 更短):用 summary 作 finalBody,compressed 标记
        → 压缩失败(null 或 summary 不变短):保留 newBody
      返回 meta.copyWith(lastAccessedAt = now).render(finalBody)   # 原子写回
    }
  → loader.modify 内部自动 reload()                     # 新记忆进入快照 + 遗忘其他过期记忆
  → notFound → 返回 error "old_text not found or memory does not exist"
  → 返回 "Saved ... (N chars) [Compressed.]"
```

### 5.5 同步压缩

```
compressMemory(llmProvider, memoryFileLimit, body)
  → llmProvider.chat(压缩到 ${memoryFileLimit/2} 字符以内)   # 失败返回 null
  → runCatching { ... }.getOrNull()   # 失败返回 null,保留 newBody
  → 返回 summary
```

压缩不是独立协程,而是 save 流程在 write 之后的一步,与 write 共享 store 的 per-name 锁。
save 返回时文件已是最终态(压缩后或未压缩),下一轮 load 直接看到。

---

## 6. 边界情况

### 6.1 `user.md` 处理

- 创建:`install` 时若不存在,创建空模板(真实元数据 + 空正文)。
- 读取:不在记忆清单中(`SharedMemoryStore.list()` 排除 `user`),且 `SharedMemoryLoadTool` 对保留名直接返回错误——
  这是必需的:**`loader.load(name)` 不再经过快照**,快照排除 user 挡不住它,必须在工具层显式拦(直接读文件就能读到)。
- 写入:`save_shared_memory("user", ...)` 允许。append 用于累积新画像条目;update 用于"忘记 xxx"/"改一下 xxx"——LLM 从 system prompt 中看到 user.md 现有内容,引用要改的片段作 old_text,提供 content(空串表示删除)。user.md 不进 loader,LLM 不能 load,所以 update 的 old_text 必须凭 system prompt 中注入的内容引用。
- 注入:`install` 时读 `user.md` 正文,通过 `addInstruction(body, "user-profile")` 注入 persona 尾部独立段落(具名 label,同名段落原位替换)。
  - **注意**:首次 install 时 user.md 为空,注入空字符串无效果。后续 LLM 通过 save_shared_memory 写入后,需要下一会话才能注入——本设计不在运行时重读 user.md 注入。这是限制,见 §8。
- 遗忘豁免:`user.md` 不在 `list()` 中,reload 永远不会碰它。

### 6.2 reload 的并发安全

- `SharedMemoryLoader.reload()` 用 `Mutex` 保护,重建后整体替换 `@Volatile` 快照。
- 触发点:`load()` / `modify()` 完成后自动调用;工具与插件不再自行触发。
- `SharedMemoryLoader.load()` 的读 + touch 走 store 的 per-name 锁,与同名 save 串行,返回值即锁内那一刻的磁盘内容。
- `reload` 内部的过期删除在 per-name 锁内二次判定,与并发 load/save 串行,不会误删刚被刷新的记忆。
- 快照中只含未过期记忆:`all()` / `get()` 返回的一定可读;读方直接读快照,无锁;reload 期间旧快照仍可读,切换原子。

### 6.3 压缩失败与并发

- **压缩位置**:压缩是 `save_shared_memory` 流程内的一步,在 `modify` 的 transform 里算完 finalBody 才一次性写入、然后才返回,与 write 共享 store 的 per-name 锁。不是独立协程。
- **失败语义**:压缩在两种情况下返回 null,save 流程保留 append/update 后的 newBody 作为最终 body:
  1. LLM 调用抛错(网络、限流等)
  2. LLM 返回的 summary 长度 ≥ 原 body(压缩无效,没变短)
  下次 save 若 `newBody.length` 仍超 `memoryFileLimit` 会再次触发压缩——这是"压缩失败则直接追加,等下一次写入时再次触发"的落点。
- **并发**:save 与 load 全程在 `store.modify` 的 per-name 锁内执行,包含 read、压缩(LLM 调用)、write。同 name 操作串行,不同 name 可并行。单进程内不存在 lost update 或压缩覆盖竞态。
- **跨进程**:不支持。若宿主跨进程共享同一 `directory`,须自行加协调层。

### 6.4 遗忘的语义

- **唯一判定点**:`SharedMemoryLoader.reload()`。`SharedMemoryLoader.load()` 与 `SharedMemorySaveTool` 都不判过期。
- **触发时机**:由 loader 的读写入口自动触发——`SharedMemoryLoader.load` / `modify` 完成各自操作后调一次 `reload()`。工具与插件都不感知,也不会遗漏。
- **清理范围**:目录中所有普通记忆(`list()` 不含 `user.md`)。所以每次读写都会"顺手"遗忘其他长期未访问的记忆,而不只是自己。
- **本次操作的记忆不会被删**:load 刚把 lastAccessedAt 刷为 now,save 刚写入 lastAccessedAt = now;reload 在 per-name 锁内二次判定,不与并发读写竞争。
- **save 对已过期记忆等于"复活"**:不判过期,直接写入并刷新 lastAccessedAt。
- **`user.md` 豁免**:不在 `list()` 中,reload 永远不会碰它。
- **没人读写就不清理**:没有后台巡检,也没有对外的清理入口(loader 不对外暴露),清理只靠下一次读写。

### 6.5 与短期 Memory 的关系

- 完全正交,互不依赖。
- 共享记忆插件不读不写 `Memory`(短期对话历史)。
- 短期 `Memory` 不知道共享记忆的存在。
- 同一个 Agent 可以同时配置短期 `Memory` 和共享记忆插件,互不干扰。

---

## 7. 测试策略

### 7.1 单元测试

- `SharedMemory.parse / bodyOf / render`:frontmatter 解析的往返一致性(render 后 parse + bodyOf 能还原元数据与正文),且 `parse` 不依赖 store(纯函数、无 IO);缺失字段、乱序字段、特殊字符、文件名与 frontmatter name 冲突时以文件名为准。
  - 缺 `lastAccessedAt` 的文件解析后 **不得** 被判为过期(兜底用 now 而非 EPOCH)
  - 没有 frontmatter / 半截 frontmatter 的文件不抛异常,正文取全文
  - `description` 含换行 / 冒号 / 中文时 render → parse 往返后与原值一致(换行已折成空格)
- `MEMORY_NAME_PATTERN`:接受 `travel` / `user` / `a.b-c_1`;拒绝 `../x`、`a/b`、空串、65 字符、`.hidden`(首字符非字母数字)。
- `PathSharedMemoryStore`:在 tempdir 上读写删列表,IO 异常路径。
- `SharedMemoryLoader` 的过期清理(`reload` 已 private,经 `load` / `modify` 间接验证):新增/删除/变更文件的同步;过期文件被删除且不进入快照;未过期文件保留。
- `SharedMemoryLoader.load`:锁内取正文并刷新 lastAccessedAt,不判过期(已超阈值的文件仍能读出);文件不存在返回 null 且不新建空文件。
- `SharedMemoryLoader.load` / `modify` 完成后快照即同步:调用返回后 `get()` / `all()` 立刻反映新状态,调用方无需(也不应)再自行调 `reload()`。
- `SharedMemoryLoader` 容错:目录中放一个不可读 / 内容异常的文件,`load` / `modify` 不抛异常,该文件不进快照也不被删除。
- `SharedMemorySaveTool.execute`:
  - append / update 两种 mode(update 配 old_text,content 作替换文本,支持局部替换与删除)
  - 非法 name(`../x`、含 `/`、超长)返回 isError,不落盘
  - 新记忆创建 → loader 内部已 reload,`loader.get` 立即可命中
  - 已有记忆更新 lastAccessedAt;已过期记忆被 save 复活(不删除)
  - 超过 memoryFileLimit → 触发同步压缩(mock LlmProvider)
  - save 后其他过期记忆被清理(loader.modify 内部 reload 生效)
- `SharedMemoryPlugin.initialize`:user.md 不存在时创建,已存在时不覆盖。
- `SharedMemoryLoadTool.execute`:保留名 `user` 返回错误(即使 `user.md` 在磁盘上存在也不返回正文);不存在的 name 返回 not found。

### 7.2 集成测试

- 用 mock `LlmProvider`,模拟 LLM 调用 `save_shared_memory("travel", "...")` → `load_shared_memory("travel")` → 返回写入内容。
- 模拟压缩:`memoryFileLimit=100`,写入 200 字符内容,验证压缩被触发且 md 最终被覆盖为压缩后内容。
- 模拟遗忘:`forgetDuration=1ms`,创建 A、B 两个记忆,load A 后令 A、B 均超期,再 load B——验证 reload 在 load 之后把 A 删掉,而 B 因刚被读取幸存。

### 7.3 不在测试范围

- 真实 LLM 调用(用 mock)。
- Android 平台适配(用 Path 在 JVM 测试)。
- 与 Skill/Subagent 共存的兼容性(共享记忆是独立 Tool,不依赖 Capability 框架,共存由 ToolRegistry 自然保证)。

---

## 8. 已知限制与未来工作

### 8.1 当前限制

1. **user.md 注入是静态的**:`install` 时一次性读 `user.md` 内容通过 `addInstruction(body, "user-profile")` 注入 persona 尾部独立段落,运行时 LLM 通过 `save_shared_memory` 更新 user.md 后,**当前会话不会重新注入**——要等下一次会话。`addInstruction` 的具名 label 支持同名段落原位替换,插件若在 install 后保留 `AgentPluginContext`,理论上可在运行期重刷该段落;但当前设计不保留 context,故仍需 `onTurnFinished` 等 turn hook 才能实现每轮重读注入,而目前插件接口只支持 `registerTool` + `addInstruction`。
2. **跨进程并发不支持**:per-name Mutex 只在单进程内生效。若多个进程/Agent 实例挂同一 `directory`,可能 lost update。宿主跨进程共享须自行加协调层。
3. **遗忘无后台巡检**:清理只随 loader 的 `load` / `modify` 内部 `reload()` 发生。宿主长时间不调工具时,过期文件会留在磁盘(下次读写或下次 install 时被清理)。需要独立巡检时,得先开放一个公开的巡检 API(`reload()` 目前是 private)。
4. **存储实现不可替换**:`SharedMemoryStore` 是 `internal`,宿主只能走 `PathSharedMemoryStore`。由于依赖 `java.nio.file`,**Android API < 26 不可用**。需要平台适配时,把接口提为 `public` 并在 DSL 加 `store` 参数即可(注入缝已预留)。

### 8.2 未来扩展

1. **user.md 动态注入**:加 turn hook,每轮开始重读 user.md 注入。
2. **定时巡检**:开放一个公开的巡检入口(把 `reload()` 提为 public 或加 `purgeExpired()`),由宿主定时调用,或后续加后台巡检策略。
3. **记忆合并**:多个相关 md 合并为一个,需要 LLM 识别"相关"。
4. **跨进程快照失效感知**:多进程共享同一 `directory` 时,A 进程 save 后 B 进程的 loader 快照仍是旧的。未来可加 FS watch 或 polling reload,使 B 进程自动感知新记忆。v1 不处理。

---

## 9. 实现顺序建议

1. 在 `settings.gradle.kts` 加 `include(":agent:memory:shared")`,建模块(模板同 `agent/skill`,`explicitApi()`,不引 serialization 插件)。
2. 实现 `SharedMemoryStore` / `SharedMemory`(纯值对象,含 frontmatter 解析与渲染)/ `SharedMemoryLoader`(含过期清理与 `load` / `modify` 入口)。
3. 实现 `SharedMemoryLoadTool`(读出路径)。
4. 实现 `SharedMemorySaveTool`(不含压缩,先跑通读写路径)。
5. 实现 `SharedMemoryPlugin` + `AgentBuilder.sharedMemory(...)`,接入 AgentBuilder。
6. 加同步压缩逻辑(并入 save 流程,与 write 同锁)。
7. 实现 reload 的过期清理,并将它收进 `load` / `modify` 内部自动调用(工具与插件不显式调)。
8. 加 user.md 初始化 + addInstruction 注入。
9. 单元测试 + 集成测试。
10. demos/agent 加一个共享记忆示例。

---

## 10. 相关文档

- `docs/superpowers/specs/2026-08-11-capability-installer-design.md` — CapabilityPlugin 抽象类设计
- `docs/memory-management.md` — core Memory(短期会话记忆)的设计
- `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/Skill.kt` — Skill 接口,本插件的对称参考
