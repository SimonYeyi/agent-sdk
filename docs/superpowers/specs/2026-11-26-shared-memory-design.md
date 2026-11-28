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
- **写入决策在 LLM**:不暴露写入 API 给宿主代码,只有 `save_shared_memory` 工具。宿主只负责传 `directory` 和可选的 `LlmProvider` / `SharedMemoryStore`。
- **存储抽象最小**:`SharedMemoryStore` 接口默认走 `Path`,允许宿主注入自定义实现。不预先做 Android/iOS 平台适配。
- **生命周期检查只在读写路径**:压缩在 `save_shared_memory` 后异步触发;遗忘在 `load_shared_memory` / `save_shared_memory` 后对当前操作的 md 同步检查。不被访问的文件不检查——这正是遗忘的目的。
- **包名独立**:新模块包名 `io.github.yeyi.agent.memory.shared`,避开 core 已有的 `io.github.yeyi.agent.memory`(防止同包跨模块冲突)。

---

## 3. 模块结构

新模块 `:agent:memory:shared`,路径 `agent/memory/shared/`,build.gradle.kts 与 `agent/skill` 模板一致。

文件清单:

```
agent/memory/shared/
├── build.gradle.kts
└── src
    ├── main/kotlin/io/github/yeyi/agent/memory/shared/
    │   ├── SharedMemory.kt          # class SharedMemory(单个记忆实体,自包含 load())
    │   ├── SharedMemoryPlugin.kt            # internal class SharedMemoryPlugin : AgentPlugin<SharedMemoryPluginConfig>
    │   ├── SharedMemoryLoader.kt            # 扫描/重载/查找记忆清单(reload),无外部注册
    │   ├── SharedMemoryStore.kt             # interface SharedMemoryStore + PathSharedMemoryStore 默认实现
    │   ├── SharedMemoryFile.kt              # md frontmatter 解析/序列化
    │   ├── SharedMemoryLoadTool.kt          # 读出工具
    │   ├── SharedMemorySaveTool.kt          # 写入工具,LLM 写入记忆
    │   └── SharedMemoryExtensions.kt        # AgentBuilder.sharedMemory(...) DSL
    └── test/kotlin/io/github/yeyi/agent/memory/shared/
        └── (各组件单元测试)
```

依赖关系:
- `api(project(":agent:core"))` — `AgentBuilder` / `AgentPlugin` / `Tool` / `AgentContext.llmProvider`
- `api(libs.kotlinx.serialization.json)` — frontmatter 解析
- `api(libs.kotlinx.coroutines.core)` — per-name Mutex(store 串行化)

---

## 4. 核心类型

### 4.1 `SharedMemory` —— 记忆实体

```kotlin
package io.github.yeyi.agent.memory.shared

import java.time.Duration
import java.time.Instant

/**
 * 一个共享记忆 —— 对应根目录下的一个 `.md` 文件。
 *
 * 由 [SharedMemoryLoader] 在 install/reload 时扫描目录动态构建,不依赖外部宿主注册。
 * `load_shared_memory(name=...)` 工具按 name 路由到对应实例,调 [load] 返回 md 全文注入 LLM 上下文。
 *
 * - "按需加载":description 告诉 LLM 何时使用,正文不预加载
 * - load() 触发 lastAccessedAt 更新和遗忘检查
 */
public class SharedMemory internal constructor(
    /** 记忆名(等于文件名,不含 .md)。 */
    public val name: String,
    /** 给 LLM 看的"何时使用本记忆"提示。 */
    public val description: String,
    private val memoryStore: SharedMemoryStore,
    private val forgetDuration: Duration,
) {
    /**
     * 加载本记忆的 md 全文(frontmatter + 正文)。
     *
     * 副作用:
     * - 更新 frontmatter 的 `lastAccessedAt` 并写回文件
     * - 触发本记忆的遗忘检查(若 lastAccessedAt 在 update 前已超 forgetDuration,则删除文件)
     *
     * 构造时持有 [memoryStore] 与 [forgetDuration],故 [load] 无需外部上下文。
     *
     * @return md 全文;若文件已被遗忘机制删除,返回 "(memory expired)" 提示
     */
    public suspend fun load(): String {
        var expired = false
        val result = memoryStore.modify(name) { current ->
            if (current == null) return@modify null
            val (frontmatter, body) = SharedMemoryFile.parse(current)
            val now = Instant.now()
            if (Duration.between(frontmatter.lastAccessedAt, now) > forgetDuration) {
                expired = true
                return@modify null
            }
            SharedMemoryFile.serialize(frontmatter.copy(lastAccessedAt = now), body)
        }
        return if (expired || result == null) "(memory expired)" else result
    }
}
```

### 4.2 `SharedMemoryLoader` —— 扫描与重载

```kotlin
package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * 共享记忆加载器,负责扫描目录、维护 name → SharedMemory 映射。
 *
 * 与 CapabilityRegistry 不同:这里没有外部注册者,清单完全由目录扫描得到,
 * 因此不提供公开的 register/remove,只有 [reload] 重扫 + 只读查找。
 *
 * - [reload] 扫描根目录所有 `.md` 文件(排除 `user.md`),解析 frontmatter,重建内存清单。
 * - 已删除的文件从清单移除;新增的文件加入清单;变更的 frontmatter 反映到 description。
 * - 线程安全:reload 在 [mutex] 内重建不可变快照,查找直接读快照,无需加锁。
 */
public class SharedMemoryLoader(
    private val memoryStore: SharedMemoryStore,
    private val forgetDuration: Duration,
) {
    private val mutex = Mutex()

    /** 不可变快照;reload 整体替换,读方无锁。 */
    @Volatile
    private var snapshot: Map<String, SharedMemory> = emptyMap()

    /** 当前已加载的所有记忆(快照)。 */
    public fun all(): List<SharedMemory> = snapshot.values.toList()

    /** 按 name 查找记忆。 */
    public fun get(name: String): SharedMemory? = snapshot[name]

    /** 重扫根目录,重建快照。 */
    public suspend fun reload(): Unit = mutex.withLock {
        val files = memoryStore.list() // 返回所有 .md 文件名,排除 user.md
        val next = snapshot.toMutableMap()

        // 移除已不存在的
        next.keys.removeAll { name -> name !in files }

        // 新增未加载的
        for (name in files) {
            if (next[name] == null) {
                val content = memoryStore.read(name) ?: continue
                val (frontmatter, _) = SharedMemoryFile.parse(content)
                next[name] = SharedMemory(
                    name = frontmatter.name,
                    description = frontmatter.description,
                    memoryStore = memoryStore,
                    forgetDuration = forgetDuration,
                )
            }
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
import java.time.Duration
import java.time.Instant

/**
 * 占位 config 类型,满足 [AgentPlugin] 的 `C : Any` 约束。
 *
 * SharedMemoryPlugin 的所有配置在构造器完成,configure block 为 no-op。
 */
public object SharedMemoryPluginConfig

/**
 * 共享记忆插件的接线类。
 *
 * 直接实现 [AgentPlugin],install 阶段完成:
 * 1. 初始化 [SharedMemoryStore] (默认 [PathSharedMemoryStore])
 * 2. 若 `user.md` 不存在,创建空模板(真实元数据 + 空正文)
 * 3. loader.reload() 加载所有现存记忆
 * 4. context.addInstruction(userBody, "user-profile") 注入 `user.md` 正文到 persona 尾部独立段落
 * 5. registerTool(SharedMemoryLoadTool(loader))  → load_shared_memory
 * 6. registerTool(SharedMemorySaveTool(loader, memoryStore, memoryFileLimit, forgetDuration, llmProvider)) → save_shared_memory
 */
internal class SharedMemoryPlugin(
    private val memoryStore: SharedMemoryStore,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
    private val llmProvider: LlmProvider?,
    private val loader: SharedMemoryLoader,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) { /* no-op,配置已在构造器完成 */ }

    override fun install(context: AgentPluginContext) {
        val userBody = runBlocking { initialize() }  // ensureUserMemory + reload,返回 user.md 正文

        // 注入 user.md 正文到 persona 尾部独立段落(具名 label,便于后续原位刷新)
        if (userBody.isNotBlank()) {
            context.addInstruction(userBody, "user-profile")
        }

        // 注册两个工具(普通 Tool,不经过 CapabilityAdapter)
        context.registerTool(SharedMemoryLoadTool(loader))
        context.registerTool(
            SharedMemorySaveTool(loader, memoryStore, memoryFileLimit, forgetDuration, llmProvider)
        )
    }

    private suspend fun initialize(): String {
        val userBody = ensureUserMemory()
        loader.reload()
        return userBody
    }

    // 若 user.md 不存在,创建空模板;返回 user.md 正文(供 install 注入)
    private suspend fun ensureUserMemory(): String {
        val now = Instant.now()
        val empty = SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter(
                name = "user",
                description = "用户画像,每轮主动注入 system prompt",
                createdAt = now,
                lastAccessedAt = now,
            ),
            body = "",
        )
        val result = memoryStore.modify("user") { current ->
            current ?: empty  // 不存在则写空模板;存在则保持原样
        }
        val (_, body) = SharedMemoryFile.parse(result ?: empty)
        return body
    }
}
```

**注意 `runBlocking` 的使用**:`install` 是同步签名(`AgentPlugin.install` 非 suspend),但 `SharedMemoryStore` 的操作是 suspend。`runBlocking` 在 install 阶段阻塞直到完成,这是插件初始化的合理代价——install 在 Agent 构建期执行,不在 LLM 推理路径上。

### 4.4 `SharedMemoryLoadTool` —— 读出工具

普通 `Tool`,不继承 `CapabilityLoadTool`。description 每轮求值(用 getter 而非 `by lazy`),拼出当前 loader 中所有记忆的 name + description 列表,使 reload 后下一轮 LLM 自动看到新记忆。execute 按 `name` 参数路由到 `SharedMemory` 实例调 `load()`(记忆自持 store 与 forgetDuration,无需上下文)。

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        arguments: kotlinx.serialization.json.JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val name = (arguments.jsonObject["name"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        val memory = loader.get(name)
            ?: return ToolExecutionResult.error("Memory not found: $name")
        return ToolExecutionResult.success(memory.load())
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * LLM 写入共享记忆的工具。
 *
 * 参数:
 * - name: 记忆名(对应文件名,不含 .md)
 * - description: 记忆描述(给 LLM 看的"何时使用本记忆"提示)。**新建记忆时必填**;已有记忆忽略此参数
 * - content: 写入内容(markdown)
 * - mode: "append" 追加到正文末尾 / "update" 局部替换(配 old_text,content 作替换文本)
 *
 * 副作用:
 * - 若 name 是新记忆,创建文件并触发 loader.reload()
 * - 更新 frontmatter.lastAccessedAt
 * - 触发本记忆遗忘检查
 * - 写入后若 newBody 超过 memoryFileLimit,同步调 LLM 压缩并覆盖写回(与 write 同一把 per-name 锁,无竞态窗口)
 *
 * 返回:写入确认信息(包含字符数、是否触发压缩)
 *
 * 并发语义:本工具自身不持锁,串行性由 [SharedMemoryStore] 的 per-name 互斥保证——
 * save 与 load、save 与压缩对同一 name 自动串行。
 */
internal class SharedMemorySaveTool(
    private val loader: SharedMemoryLoader,
    private val memoryStore: SharedMemoryStore,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
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
        val description = arguments.jsonObject["description"]?.jsonPrimitive?.content
        val mode = arguments.jsonObject["mode"]?.jsonPrimitive?.content ?: "append"
        val content = arguments.jsonObject["content"]?.jsonPrimitive?.content
            ?: if (mode == "append") return ToolExecutionResult.error("Missing 'content'") else ""
        val oldText = arguments.jsonObject["old_text"]?.jsonPrimitive?.content
            ?: if (mode == "update") return ToolExecutionResult.error("Missing 'old_text'") else null

        val llmProvider = llmProvider ?: context.agentContext.llmProvider
        val now = Instant.now()

        // 状态变量:由 transform 内部写入,execute 外层读取以决定返回值
        var expired = false          // 遗忘触发删除
        var notFound = false         // update 模式 old_text 找不到 / update 不存在
        var missingDescription = false  // 新建记忆但 description 为空
        var newCreated = false       // 新记忆创建
        var finalLen = 0             // 最终正文长度
        var compressed = false       // 是否触发压缩

        memoryStore.modify(name) { current ->
            val (frontmatter, body) = if (current == null) {
                if (description.isNullOrBlank()) { missingDescription = true; return@modify null }
                newCreated = true
                SharedMemoryFile.Frontmatter(name, description, now, now) to ""
            } else {
                val (fm, b) = SharedMemoryFile.parse(current)
                fm to b  // 保留原 lastAccessedAt 用于遗忘检查;description 沿用旧值,忽略入参
            }

            // 遗忘检查:user 豁免;若已超阈值,返回 null 触发 delete
            if (name != "user" && frontmatter.lastAccessedAt.let { Duration.between(it, now) > forgetDuration }) {
                expired = true
                return@modify null
            }

            val newBody = when (mode) {
                "append" -> if (body.isBlank()) content else "$body\n\n$content"
                "update" -> {
                    if (current == null) { notFound = true; return@modify null }
                    if (!body.contains(oldText!!)) { notFound = true; return@modify null }
                    body.replace(oldText, content)
                }
                else -> { notFound = true; return@modify null }
            }

            val updatedFrontmatter = frontmatter.copy(lastAccessedAt = now)
            val finalBody = if (newBody.length > memoryFileLimit) {
                compressMemory(llmProvider, memoryFileLimit, newBody)?.also { compressed = it.length < newBody.length } ?: newBody
            } else {
                newBody
            }
            finalLen = finalBody.length
            SharedMemoryFile.serialize(updatedFrontmatter, finalBody)
        }

        when {
            expired -> {
                loader.reload()
                return ToolExecutionResult.success("Memory '$name' was expired and deleted.")
            }
            missingDescription -> return ToolExecutionResult.error(
                "Missing 'description': required when creating a new memory '$name'"
            )
            notFound -> return ToolExecutionResult.error(
                if (mode == "update") "old_text not found or memory '$name' does not exist" else "Invalid mode: $mode"
            )
            newCreated -> loader.reload()
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
 * 共享记忆文件存储抽象。默认实现 [PathSharedMemoryStore] 走文件系统,宿主可注入自定义实现。
 *
 * 并发语义:[modify] 对同一 [name] 串行执行——transform 在 per-name 锁内运行,
 * 使 save/load/压缩等复合操作(read → 计算 → write)对同一 name 原子化,
 * 消除单进程内的 lost update 与覆盖竞态。不同 name 之间可并行。
 *
 * [exists] 与 [list] 为只读,无需串行。
 */
public interface SharedMemoryStore {
    /**
     * 在 per-name 锁内读取当前内容,交给 [transform] 计算新内容,再原子写回。
     *
     * - [transform] 收到当前 md 全文(frontmatter + 正文);文件不存在时收到 null
     * - [transform] 返回新内容;返回 null 表示删除文件
     * - transform 内部可执行 suspend 操作(如调 LLM 压缩),全程持锁,不会被同名操作插入
     * - 返回值为最终写入(或删除前的)内容,供调用方使用
     */
    public suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String?

    /**
     * 只读当前内容,不加锁。文件不存在返回 null。
     * 用于 reload 等扫描场景——读到的可能是旧值或新值(reload 是最终一致快照,可接受)。
     * load/save 等需要原子性的路径应走 [modify],不要用 read + 外部 write 组合。
     */
    public suspend fun read(name: String): String?

    public suspend fun exists(name: String): Boolean
    public suspend fun list(): List<String> // 返回所有 .md 文件名(不含 .md 后缀),排除 "user"
}

/**
 * 基于 [Path] 的 [SharedMemoryStore] 默认实现。
 *
 * - 文件名:`$directory/$name.md`
 * - IO 走 [Dispatchers.IO]
 * - "user" 文件存在但不列入 list()(避免被 loader 当作普通记忆加载)
 * - per-name 互斥:[locks] 为每个 name 维护一把 [Mutex],[modify] 全程持锁,
 *   使 save 与压缩、save 与 load 对同一 name 串行
 * - write 走原子写入:先写 `.tmp` 再 [Files.move] 带 [StandardCopyOption.ATOMIC_MOVE] 替换目标,
 *   避免写一半中断导致文件损坏
 */
public class PathSharedMemoryStore(private val directory: Path) : SharedMemoryStore {
    init { directory.toFile().mkdirs() }

    private val locks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withNameLock(name: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(name) { Mutex() }
        return mutex.withLock { block() }
    }

    override suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? = withNameLock(name) {
        val target = directory.resolve("$name.md").toFile()
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
        val f = directory.resolve("$name.md").toFile()
        if (f.exists()) f.readText() else null
    }

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        directory.resolve("$name.md").toFile().exists()
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        directory.toFile().listFiles { f -> f.extension == "md" && f.nameWithoutExtension != "user" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
```

### 4.7 `SharedMemoryFile` —— frontmatter + Markdown

```kotlin
package io.github.yeyi.agent.memory.shared

import java.time.Instant

/**
 * 共享记忆 md 文件的 frontmatter。
 *
 * - name: 记忆名(等于文件名,不含 .md)
 * - description: 给 LLM 看的"何时使用本记忆"提示
 * - createdAt: 创建时间
 * - lastAccessedAt: 最后一次 load/save 时间(用于遗忘判断)
 */
public data class SharedMemoryFile(
    public val frontmatter: Frontmatter,
    public val body: String,
) {
    public data class Frontmatter(
        public val name: String,
        public val description: String,
        public val createdAt: Instant,
        public val lastAccessedAt: Instant,
    )

    public companion object {
        public fun parse(content: String): SharedMemoryFile { /* YAML frontmatter 解析 */ }
        public fun serialize(frontmatter: Frontmatter, body: String): String { /* 反向序列化 */ }
    }
}
```

### 4.8 `SharedMemoryExtensions` —— DSL

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path
import java.time.Duration

/**
 * 注册共享记忆插件到 Agent。
 *
 * @param directory 记忆文件根目录,宿主必传
 * @param memoryFileLimit 单文件字符上限,超则同步压缩。默认 8000
 * @param forgetDuration 遗忘时间,文件超过此时长未被读写则删。默认 30 天
 * @param llmProvider 压缩用 LLM;null 时回退到 AgentContext.llmProvider。默认 null
 */
public fun AgentBuilder.sharedMemory(
    directory: Path,
    memoryFileLimit: Int = 8000,
    forgetDuration: Duration = Duration.ofDays(30),
    llmProvider: LlmProvider? = null,
) {
    val store = PathSharedMemoryStore(directory)
    val loader = SharedMemoryLoader(store, forgetDuration)
    val plugin = SharedMemoryPlugin(
        memoryFileLimit = memoryFileLimit,
        forgetDuration = forgetDuration,
        llmProvider = llmProvider,
        memoryStore = store,
        loader = loader,
    )
    plugin(plugin)  // configure block 为空,initialize 在 install 内部触发
}
```

---

## 5. 数据流

### 5.1 install 阶段

```
AgentBuilder.sharedMemory(directory, ...)
  → 创建 SharedMemoryLoader(memoryStore, forgetDuration)
  → 创建 SharedMemoryPlugin(...)  // 直接实现 AgentPlugin
  → plugin(plugin)
    → SharedMemoryPlugin.install(context)
      → runBlocking { initialize() }
        → PathSharedMemoryStore(directory)
        → ensureUserMemory(store)        # 若 user.md 不存在,创建空模板
        → loader.reload()                # 扫描所有 .md(排除 user),重建快照
      → store.read("user")               # 读 user.md 正文
      → context.addInstruction(body, "user-profile") # 注入到 persona 尾部独立段落
      → context.registerTool(SharedMemoryLoadTool(loader))                    # load_shared_memory
      → context.registerTool(SharedMemorySaveTool(loader, store, memoryFileLimit, forgetDuration, llmProvider)) # save_shared_memory
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
  → loader.get("travel")
  → memory.load()
      → memoryStore.modify("travel") { current ->           # per-name 锁内
          parse(current) → (frontmatter, body)
          遗忘检查: if (name != "user" && now - frontmatter.lastAccessedAt > forgetDuration)
            → 返回 null 触发删除 + reload + 外层返回 "(expired)"
          更新 frontmatter.lastAccessedAt = now
          返回 serialize(frontmatter, body)                 # 原子写回
        }
      → 返回 md 全文(或 "(expired)")
  → ToolExecutionResult.success(content)
```

### 5.4 LLM 调用 save_shared_memory(name, content, mode)

```
save_shared_memory.execute({name, content, mode, old_text?})
  → 解析参数
  → memoryStore.modify(name) { current ->                  # per-name 锁内,整流程原子
      current == null → isNew,创建 Frontmatter(name, desc, now, now),body = ""
      current != null → parse,保留原 lastAccessedAt 用于遗忘检查
      遗忘检查:user 豁免;超阈值 → 返回 null 触发删除 + expired 标记
      mode=append: body + "\n\n" + content
      mode=update:
        current == null → notFound 标记,返回 null
        body.contains(old_text) 失败 → notFound 标记,返回 null
        body.replace(old_text, content)   # content="" 表示删除
      更新 frontmatter.lastAccessedAt = now
      若 newBody.length > memoryFileLimit:
        → 同步调 compressMemory(newBody)   # 仍在 modify 锁内
        → 压缩成功(summary 更短):用 summary 作 finalBody,compressed 标记
        → 压缩失败(null 或 summary 不变短):保留 newBody
      返回 serialize(frontmatter, finalBody)   # 原子写回
    }
  → expired → reload + 返回 "expired and deleted"
  → notFound → 返回 error "old_text not found or memory does not exist"
  → isNew → reload
  → 返回 "Saved ... (N chars) [Compressed.]"
  → 返回 ToolExecutionResult.success("Saved ... (N chars) [Compressed.]")
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

## 7. 边界情况

### 7.1 `user.md` 处理

- 创建:`install` 时若不存在,创建空模板(真实元数据 + 空正文)。
- 读取:不进入 loader(`SharedMemoryStore.list()` 排除 `user`),LLM 不能 `load_shared_memory("user")`。
- 写入:`save_shared_memory("user", ...)` 允许。append 用于累积新画像条目;update 用于"忘记 xxx"/"改一下 xxx"——LLM 从 system prompt 中看到 user.md 现有内容,引用要改的片段作 old_text,提供 content(空串表示删除)。user.md 不进 loader,LLM 不能 load,所以 update 的 old_text 必须凭 system prompt 中注入的内容引用。
- 注入:`install` 时读 `user.md` 正文,通过 `addInstruction(body, "user-profile")` 注入 persona 尾部独立段落(具名 label,同名段落原位替换)。
  - **注意**:首次 install 时 user.md 为空,注入空字符串无效果。后续 LLM 通过 save_shared_memory 写入后,需要下一会话才能注入——本设计不在运行时重读 user.md 注入。这是限制,见 §9。
- 遗忘豁免:`user.md` 不参与遗忘检查。

### 7.2 reload 的并发安全

- `SharedMemoryLoader.reload()` 用 `Mutex` 保护,重建后整体替换 `@Volatile` 快照。
- `save_shared_memory` 创建新文件后同步调 `reload`,保证下一轮 `load_shared_memory` 能找到新记忆。
- `load_shared_memory` 路径不触发 reload(只读已有快照)。
- 查找 `all()` / `get()` 直接读快照,无锁;reload 期间旧快照仍可读,切换原子。

### 7.3 压缩失败与并发

- **压缩位置**:压缩是 `save_shared_memory` 流程内的一步,在 `store.write(newBody)` 之后、返回之前,与 write 共享 store 的 per-name 锁。不是独立协程。
- **失败语义**:压缩在两种情况下返回 null,save 流程保留 append/update 后的 newBody 作为最终 body:
  1. LLM 调用抛错(网络、限流等)
  2. LLM 返回的 summary 长度 ≥ 原 body(压缩无效,没变短)
  下次 save 若 `newBody.length` 仍超 `memoryFileLimit` 会再次触发压缩——这是"压缩失败则直接追加,等下一次写入时再次触发"的落点。
- **并发**:save 与 load 全程在 `store.modify` 的 per-name 锁内执行,包含 read、压缩(LLM 调用)、write。同 name 操作串行,不同 name 可并行。单进程内不存在 lost update 或压缩覆盖竞态。
- **跨进程**:不支持。若宿主跨进程共享同一 `directory`,须自行加协调层。

### 7.4 遗忘检查的语义

- 检查时机:`load_shared_memory` / `save_shared_memory` 后,**只对当前操作的 md** 检查。
- 检查逻辑:若 `now - lastAccessedAt > forgetDuration` → 删除文件 + reload。
  - `load_shared_memory`:更新 lastAccessedAt 前先检查旧值;若已超阈值,删除并返回 "(expired)"。
  - `save_shared_memory`:同上,但 save 是写入路径,语义上是"主动访问",原则上不应触发遗忘。但为了与 load 一致,仍检查;若触发遗忘则 save 不执行。
- `user.md` 豁免。
- 不被访问的文件不检查——这正是遗忘的目的(长期没被读写的文件应该被遗忘,不需要主动巡检)。

### 7.5 与短期 Memory 的关系

- 完全正交,互不依赖。
- 共享记忆插件不读不写 `Memory`(短期对话历史)。
- 短期 `Memory` 不知道共享记忆的存在。
- 同一个 Agent 可以同时配置短期 `Memory` 和共享记忆插件,互不干扰。

---

## 8. 测试策略

### 8.1 单元测试

- `SharedMemoryFile.parse / serialize`:frontmatter 解析的往返一致性,缺失字段、乱序字段、特殊字符。
- `PathSharedMemoryStore`:在 tempdir 上读写删列表,IO 异常路径。
- `SharedMemoryLoader.reload`:新增/删除/变更文件的同步。
- `SharedMemorySaveTool.execute`:
  - append / update 两种 mode(update 配 old_text,content 作替换文本,支持局部替换与删除)
  - 新记忆创建 → 触发 reload
  - 已有记忆更新 lastAccessedAt
  - 超过 memoryFileLimit → 触发同步压缩(mock LlmProvider)
  - 遗忘触发删除(mock clock)
  - user.md 豁免遗忘
- `SharedMemoryPlugin.initialize`:user.md 不存在时创建,已存在时不覆盖。

### 8.2 集成测试

- 用 mock `LlmProvider`,模拟 LLM 调用 `save_shared_memory("travel", "...")` → `load_shared_memory("travel")` → 返回写入内容。
- 模拟压缩:`memoryFileLimit=100`,写入 200 字符内容,验证压缩被触发且 md 最终被覆盖为压缩后内容。
- 模拟遗忘:`forgetDuration=1ms`,load 后 sleep 10ms,再次 load,验证文件被删除。

### 8.3 不在测试范围

- 真实 LLM 调用(用 mock)。
- Android 平台适配(用 Path 在 JVM 测试)。
- 与 Skill/Subagent 共存的兼容性(共享记忆是独立 Tool,不依赖 Capability 框架,共存由 ToolRegistry 自然保证)。

---

## 9. 已知限制与未来工作

### 9.1 当前限制

1. **user.md 注入是静态的**:`install` 时一次性读 `user.md` 内容通过 `addInstruction(body, "user-profile")` 注入 persona 尾部独立段落,运行时 LLM 通过 `save_shared_memory` 更新 user.md 后,**当前会话不会重新注入**——要等下一次会话。`addInstruction` 的具名 label 支持同名段落原位替换,插件若在 install 后保留 `AgentPluginContext`,理论上可在运行期重刷该段落;但当前设计不保留 context,故仍需 `onTurnFinished` 等 turn hook 才能实现每轮重读注入,而目前插件接口只支持 `registerTool` + `addInstruction`。
2. **跨进程并发不支持**:per-name Mutex 只在单进程内生效。若多个进程/Agent 实例挂同一 `directory`,可能 lost update。宿主跨进程共享须自行加协调层。
3. **遗忘只检查当前 md**:不被访问的文件不会被主动遗忘巡检。如果用户希望"定期清理所有超期文件",需要宿主应用主动调 `loader.reload()`(reload 不删除文件,只是同步快照)——本设计不提供独立的遗忘巡检 API。

### 9.2 未来扩展

1. **user.md 动态注入**:加 turn hook,每轮开始重读 user.md 注入。
2. **遗忘巡检 API**:`SharedMemoryLoader.purgeExpired()` 主动清理所有超期文件。
3. **记忆合并**:多个相关 md 合并为一个,需要 LLM 识别"相关"。
4. **跨进程快照失效感知**:多进程共享同一 `directory` 时,A 进程 save 后 B 进程的 loader 快照仍是旧的。未来可加 FS watch 或 polling reload,使 B 进程自动感知新记忆。v1 不处理。

---

## 10. 实现顺序建议

1. 新建 `agent/memory/shared` 模块,实现 `SharedMemoryStore` / `SharedMemoryFile` / `SharedMemory` / `SharedMemoryLoader`。
2. 实现 `SharedMemoryLoadTool`(读出路径)。
3. 实现 `SharedMemorySaveTool`(不含压缩,先跑通读写路径)。
4. 实现 `SharedMemoryPlugin` + `SharedMemoryExtensions`,接入 AgentBuilder。
5. 加同步压缩逻辑(并入 save 流程,与 write 同锁)。
6. 加遗忘检查。
7. 加 user.md 初始化 + addInstruction 注入。
8. 单元测试 + 集成测试。
9. demos/agent 加一个共享记忆示例。

---

## 11. 相关文档

- `docs/superpowers/specs/2026-08-11-capability-installer-design.md` — CapabilityPlugin 抽象类设计
- `docs/memory-management.md` — core Memory(短期会话记忆)的设计
- `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/Skill.kt` — Skill 接口,本插件的对称参考
