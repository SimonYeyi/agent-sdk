# 共享记忆插件实现计划

> **权威设计**：`docs/superpowers/specs/2026-11-26-shared-memory-design.md`（下称 spec）。
> 本计划只回答"按什么顺序写哪些代码、每步怎么验证"。
> 计划中的代码是**骨架与陷阱提示**；与 spec 冲突时一律以 spec 为准。

**目标**：新增 `:agent:memory:shared` 模块，让 Agent 通过两个工具（`load_shared_memory` / `save_shared_memory`）跨会话读写 markdown 记忆文件，并把 `user.md` 的正文作为"用户画像"注入 system prompt。

**完成定义**：
1. `./gradlew :agent:memory:shared:test` 全绿；
2. 模块对外只暴露 `AgentBuilder.sharedMemory(...)` 一个函数；
3. `demos/agent` 里能用同一目录在两次运行之间读到上次写入的记忆（可选，Task 9）。

---

## Global Constraints

### 模块与依赖

- 模块路径 `agent/memory/shared/`，Gradle 坐标 `:agent:memory:shared`。
- `kotlin("jvm")` + `jvmToolchain(21)` + `explicitApi()`，**不引** `kotlin.serialization` 插件（没有 `@Serializable` 类，只用 `JsonElement` 运行时 API）。
- 依赖方向：`api(project(":agent:core"))` + `implementation` 序列化 JSON 与 coroutines；测试栈与 `agent/skill` 一致（`kotlin.test` / `kotlin.test.junit` / `coroutines.test` / `junit`，`tasks.test { useJUnit() }`），**外加** `testImplementation(testFixtures(project(":agent:core")))`——压缩路径复用 core testFixtures 的 `FakeLlmProvider`（`io.github.yeyi.agent.fakes`），不自己再写一份。
- 仅 JVM：依赖 `java.nio.file.Path` 与 `java.time`，Android 侧需 API 26+。

### 公开面

**唯一公开 API 是 `AgentBuilder.sharedMemory(directory, maxFileCount, maxFileCharSize, llmProvider)`。**

除它之外所有类型（`SharedMemory` / `SharedMemoryLoader` / `SharedMemoryStore` / `PathSharedMemoryStore` / 两个 Tool / `SharedMemoryPlugin` / `MEMORY_NAME_PATTERN`）一律 `internal`。宿主拿不到 loader，因此也就不存在"忘记调 reload"或"绕过单例另造 store"的路径。

### 禁止出现的旧结构

以下类型与方法在 spec 演进中已被删除，**本计划任何地方都不应再出现**（实现时若写出来即为方向错误）：

| 已删除 | 曾用于 | 现在的替代 |
|---|---|---|
| `SharedMemoryFile` | 独立的文件模型 | 合并进 `SharedMemory`（实体即文件） |
| `SharedMemoryContext` / `SharedMemoryContextFactory` | 执行上下文 | 删除：loader 独立、无上下文 |
| `SharedMemoryRegistry` / `rescan()` | 外部注册记忆 | 删除：清单完全来自目录扫描 |
| `SharedMemory.load()` | 实体自己读文件 | `SharedMemoryLoader.load(name)` |
| `forgetDuration` / `isExpired` / `DEFAULT_FORGET_*` | 时间遗忘 | 删除：改为条数淘汰 |
| `maxTotalSize` / `byteSize()` | 磁盘字节容量 | 删除：`maxFileCount`（条）+ `maxFileCharSize`（字符） |
| `AgentBuilder.memory(directory, ...)` | 旧 DSL 名 | `AgentBuilder.sharedMemory(...)`（`memory` 已被 core 成员函数占用） |
| `USER_PROFILE_LABEL` 常量 | user 段落 label | 内联字面量 `"user-profile"` |

### 四条不可协商的语义

1. **正文一行一条**：`append` 用 `"\n"` 追加；`update` 的 `old_text` 必须与正文中**某一整行** `trim()` 后严格相等，命中后整行替换，`content` 为空串则删除该行（`removeAt`，不留空行）。这是条目替换，不是词句替换。
2. **`store.modify` 的 `null` 只表示"删除文件"**：所有失败路径（找不到 old_text、缺 description、超硬上限、非法 mode）必须 `return@modify current`，绝不能 `return@modify null`。
3. **`reload()` 只能放在 `store.modify(...)` 返回之后**：它会对淘汰候选再进一次 per-name 锁，放进 `transform` 里会在同一把不可重入的 `Mutex` 上自锁死。`reload` 是 `private`，只由 `load` / `modify` 内部调用。
4. **锁必须是 `kotlinx.coroutines.sync.Mutex`**：`transform` 是 `suspend` 且内部可能调 LLM（压缩），用 `Any` + `synchronized` 会在挂起期间占死 carrier 线程。

### 尺寸与条数

| 参数 | 维度 | 语义 |
|---|---|---|
| `maxFileCount` | 条数，默认 20 | 目录里最多保留几条；超出按 `(lastAccessedAt, name)` 升序淘汰到刚好回到上限 |
| `maxFileCharSize` | 字符数，默认 8000 | **软上限**：超限尝试同步压缩，压缩失败则原文照存 |
| `MAX_COMPRESS_INPUT_FACTOR` | 常量 4 | **硬门**：`newBody.length > maxFileCharSize × 4` 直接返回 error 且不落盘 |

### 工作方式

- 一个 Task = 一次提交。提交信息正文用中文，`type(scope)` 保留英文，例如 `feat(memory-shared): SharedMemoryLoader 的条数淘汰与快照`。
- 每个 Task 结束必须跑通该模块的测试命令，**不允许带着红灯进入下一个 Task**。
- 测试不碰真实 LLM：压缩路径一律注入 mock `LlmProvider`。

---

## File Structure

```
agent/memory/shared/
├── build.gradle.kts
└── src
    ├── main/kotlin/io/github/yeyi/agent/memory/shared/
    │   ├── SharedMemory.kt            # 纯值对象：四字段 + parse/bodyOf/render/create（data class 自带 copy/equals）
    │   ├── SharedMemoryStore.kt       # MEMORY_NAME_PATTERN + 接口 + PathSharedMemoryStore
    │   ├── SharedMemoryLoader.kt      # 按目录单例 of()、load/modify 入口、快照、条数淘汰
    │   ├── SharedMemoryLoadTool.kt    # load_shared_memory
    │   ├── SharedMemorySaveTool.kt    # save_shared_memory（条目写入 + 压缩 + 硬门）
    │   ├── SharedMemoryPlugin.kt      # AgentPlugin 接线 + user 初始化与写时刷新
    │   └── SharedMemoryExtensions.kt  # AgentBuilder.sharedMemory(...) —— 唯一公开入口
    └── test/kotlin/io/github/yeyi/agent/memory/shared/
        ├── FakeSharedMemoryStore.kt   # 内存 store（测试替身）
        ├── SharedMemoryTest.kt
        ├── SharedMemoryStoreTest.kt
        ├── SharedMemoryLoaderTest.kt
        ├── SharedMemoryLoadToolTest.kt
        ├── SharedMemorySaveToolTest.kt
        ├── SharedMemoryPluginTest.kt
        └── SharedMemoryIntegrationTest.kt
```

依赖方向（单向，无回指）：

```
SharedMemoryExtensions
  └─> SharedMemoryPlugin
        ├─> SharedMemoryLoadTool ─┐
        └─> SharedMemorySaveTool ─┤
                                  └─> SharedMemoryLoader ─> { SharedMemory, SharedMemoryStore }
```

`SharedMemory` 不持有 store、不知道 loader 存在；`SharedMemoryStore` 不认识 frontmatter（只搬字符串）；两个 Tool 不认识快照（只知道 `loader.load` / `loader.modify`）。

---

## Task 1: 模块骨架与 Gradle 接线

**目标**：空模块能编译、能被 Gradle 识别，为后续所有 Task 提供落点。

**文件**：
- 改 `settings.gradle.kts`
- 新建 `agent/memory/shared/build.gradle.kts`
- 新建包目录 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/`

**步骤**

1. 在 `settings.gradle.kts` 的 agent 模块区块里加一行（放在 `include(":agent:subagent")` 之后、`:agent:toolset` 之前，保持分组）：

```kotlin
include(":agent:memory:shared")
```

2. 新建 `agent/memory/shared/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":agent:core"))                 // AgentBuilder / AgentPlugin / Tool
    implementation(libs.kotlinx.serialization.json)   // 只用 JsonElement，不加插件
    implementation(libs.kotlinx.coroutines.core)      // Mutex / runBlocking

    testImplementation(testFixtures(project(":agent:core")))   // FakeLlmProvider
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
```

> 注意：`agent/skill` 的模板里有 `alias(libs.plugins.kotlin.serialization)` 与 `api(project(":agent:capability"))`，**这两条都不要抄**——本模块没有 `@Serializable` 类，也不走 Capability 框架。

3. 建空包目录（Kotlin 不强制，但 IDE 与 `explicitApi` 的检查需要源集存在）。

**验证**

```bash
./gradlew :agent:memory:shared:compileKotlin
```

期望：`BUILD SUCCESSFUL`。此时还没有任何源文件，编译任务可能直接跳过——可先写一个占位的 `internal object ModulePlaceholder` 验证工具链，Task 2 删除。

**完成标志**：`./gradlew projects` 的输出里出现 `:agent:memory:shared`。

---

## Task 2: `SharedMemory` —— 值对象与 md 编解码

**目标**：一个**纯函数**级别的记忆模型：给定 md 全文能解析出元数据与正文，给定元数据 + 正文能渲染回 md，往返无损。本 Task 不碰磁盘。

**文件**：`SharedMemory.kt`、`SharedMemoryTest.kt`

**spec 依据**：§4.1（权威代码见该节）。

**步骤**

1. 定义类型骨架（字段、可见性、KDoc 照 spec §4.1）：

```kotlin
internal data class SharedMemory(
    val name: String,
    val description: String,
    val createdAt: Instant,
    val lastAccessedAt: Instant,
) {
    fun render(body: String): String = buildString {
        append("---\n")
        append("name: ").append(name).append('\n')
        append("description: ").append(description.replace('\n', ' ')).append('\n')
        append("createdAt: ").append(createdAt).append('\n')
        append("lastAccessedAt: ").append(lastAccessedAt).append('\n')
        append("---\n")
        append(body)
    }

    companion object {
        const val RESERVED_USER: String = "user"
        fun parse(name: String, content: String): SharedMemory = /* 见步骤 2 */
        fun bodyOf(content: String): String = /* 见步骤 3 */
        fun create(name: String, description: String, now: Instant): SharedMemory =
            SharedMemory(name, description, createdAt = now, lastAccessedAt = now)
    }
}
```

> `description` 渲染前把换行折成空格，是因为 frontmatter 的每个值必须单行；解析端按"第一个 `:` 切分 + trim"读回，两边规则必须对称。**不要**引入 YAML 库或引号转义。

2. 实现 `parse`。frontmatter 的判定与兜底：

```kotlin
fun parse(name: String, content: String): SharedMemory {
    val lines = content.lines()

    // 没有 frontmatter（或首行不是 ---）：整段都是正文，全部兜底
    if (lines.firstOrNull()?.trim() != "---") return create(name, "", Instant.now())

    // 找结束分隔线；未闭合同样按"没有 frontmatter"处理（宁可当新，不要误解析半截）
    val closing = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (closing < 0) return create(name, "", Instant.now())

    val fields = HashMap<String, String>()
    for (line in lines.subList(1, closing + 1)) {
        val sep = line.indexOf(':')
        if (sep <= 0) continue                       // 空行 / 没有 key 的行跳过
        fields[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
    }

    val now = Instant.now()
    fun time(key: String): Instant? =
        fields[key]?.let { runCatching { Instant.parse(it) }.getOrNull() }

    val created = time("createdAt") ?: time("lastAccessedAt") ?: now
    val accessed = time("lastAccessedAt") ?: created

    return SharedMemory(
        name = name,                     // 文件名优先，忽略 frontmatter 里的 name
        description = fields["description"].orEmpty(),
        createdAt = created,
        lastAccessedAt = accessed,
    )
}
```

**兜底口径（务必照做）**：缺 `createdAt` 用 `lastAccessedAt`，缺 `lastAccessedAt` 用 `createdAt`，两者都缺或解析失败用 `now`——**不能用 `Instant.EPOCH`**，否则这条记忆永远是条数淘汰的第一个。

3. 实现 `bodyOf`，与 `parse` 共用同一套分隔线定位规则：

```kotlin
fun bodyOf(content: String): String {
    val lines = content.lines()
    if (lines.firstOrNull()?.trim() != "---") return content
    val closing = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (closing < 0) return content
    return lines.drop(closing + 2).joinToString("\n")
}
```

> 索引关系（容易错）：`closing` 是 `lines.drop(1)` 里的下标，对应原 `lines` 的下标是 `closing + 1`；正文从它再往后一行开始，即 `drop(closing + 2)`。

4. 写 `SharedMemoryTest`，覆盖：

| 用例 | 断言 |
|---|---|
| `render` → `parse` + `bodyOf` 往返 | 四个字段与正文全部还原 |
| 无 frontmatter 的纯文本 | 不抛异常，正文 = 全文，`description` 为空串，时间 = 解析时刻 |
| frontmatter 未闭合 | 同上（全文作正文） |
| 缺 `lastAccessedAt` | 解析后 `lastAccessedAt == createdAt`，且**不是** `EPOCH` |
| 两个时间戳都缺 | 落在 `[解析前, 解析后]` 区间内 |
| 时间戳格式非法（如 `2026-01-01`） | 不抛异常，走兜底 |
| 文件名与 frontmatter 的 `name` 冲突 | 以文件名为准 |
| `description` 含 `\n` | render 后只有一个空格，parse 回来是折行后的值 |
| `description` 含 `:` 与中文 | 往返一致（只按第一个 `:` 切分） |
| 正文为空 | `render("")` 后 `bodyOf` 返回 `""`，不产生多余空行 |
| 正文多行 | `bodyOf` 返回的行由 `\n` 连接，行序不变 |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryTest'
```

**完成标志**：上述用例全绿；`SharedMemory` 里没有任何 `java.io` / `java.nio` 引用。

---

## Task 3: `SharedMemoryStore` 与 `PathSharedMemoryStore`

**目标**：把"读改写同一条记忆"变成原子操作，并把文件名规则收在一处。

**文件**：`SharedMemoryStore.kt`、`FakeSharedMemoryStore.kt`（test）、`SharedMemoryStoreTest.kt`

**spec 依据**：§4.6。

**步骤**

1. 定义名字规则与接口：

```kotlin
internal val MEMORY_NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

internal interface SharedMemoryStore {
    suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String?
    suspend fun read(name: String): String?
    suspend fun exists(name: String): Boolean
    suspend fun list(): List<String>
}
```

`modify` 的契约必须写进 KDoc，三条都要说清：`current` 为 `null` 表示文件不存在；`transform` 返回 `null` **表示删除文件**（不是"无操作"）；**整段在 per-name 锁内执行**，所以 transform 里可以安全地调 LLM。

2. 实现 `PathSharedMemoryStore`：

```kotlin
internal class PathSharedMemoryStore(private val directory: Path) : SharedMemoryStore {
    init { directory.toFile().mkdirs() }

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
        withContext(Dispatchers.IO) {
            if (result == null) {
                target.delete()
            } else {
                val tmp = directory.resolve("$name.md.tmp")
                Files.write(tmp.toPath(), result.toByteArray(Charsets.UTF_8))
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }
        result
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) { /* 存在则 readText，否则 null */ }

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) { fileOf(name).exists() }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        directory.toFile().listFiles { f ->
            f.extension == "md" &&
                f.nameWithoutExtension != SharedMemory.RESERVED_USER &&
                MEMORY_NAME_PATTERN.matches(f.nameWithoutExtension)
        }?.map { it.nameWithoutExtension } ?: emptyList()
    }
}
```

四个必须注意的点：

- `list()` 的三重过滤缺一不可：排除 `user`（否则它会被淘汰）、只认合法名（否则中文名 / 带空格的文件会进快照并被淘汰）、只认 `.md`（`.tmp` 残留不能算记忆）。
- `read` / `exists` 不加锁：它们是只读的，`reload` 用它扫描全目录，读到旧值可接受。
- `ATOMIC_MOVE` 在个别文件系统（部分 FUSE / 外部存储）会抛 `AtomicMoveNotSupportedException`。v1 按 spec 不处理回退——目标是 app 私有目录。若将来接入外部存储，在此补 `runCatching { ATOMIC_MOVE }` 回退到 `REPLACE_EXISTING`。
- `init` 里的 `mkdirs()` 忽略返回值是故意的：目录已存在时它返回 `false`，不是错误。

3. 写测试替身 `FakeSharedMemoryStore`（放 `src/test`，不碰磁盘）：

```kotlin
internal class FakeSharedMemoryStore : SharedMemoryStore {
    private val files = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 让指定 name 的 read 抛异常，用于验证 reload 的单文件容错。 */
    var failOnRead: Set<String> = emptySet()

    /** 测试断言用：直接看当前落盘内容。 */
    fun contentOf(name: String): String? = files[name]

    override suspend fun modify(name: String, transform: suspend (String?) -> String?): String? =
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
```

> 替身也必须实现 per-name 锁，否则 Task 4 的并发用例在替身上会假通过。

4. 写 `SharedMemoryStoreTest`（用 JUnit `@TempDir`）：

| 用例 | 断言 |
|---|---|
| `modify` 新建 → `read` | 内容一致，文件存在于 `dir/name.md` |
| `modify` 返回 `null` | 文件被删除，`exists` 为 false |
| `list()` 过滤 | 目录里放 `travel.md`、`user.md`、`中文.md`、`a b.md`、`x.md.tmp` → 只返回 `travel` |
| 非法 name | `modify("../x")` 抛 `IllegalArgumentException`，目录外没有文件被创建 |
| 写入中断的残留 | 手工造一个 `x.md.tmp`，`list()` 不含它，且能被后续 `modify` 覆盖 |
| 并发同名写 | 用 `coroutineScope` 起 20 个 `modify("a") { (it ?: "") + "x" }`，最终内容长度为 20（无 lost update） |
| 文本编码 | 写入含中文与 emoji 的正文，读回逐字符相等 |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryStoreTest'
```

**完成标志**：并发同名写用例稳定通过（重复跑 5 次无失败）。

---

## Task 4: `SharedMemoryLoader` —— 单例、快照与条数淘汰

**目标**：把 store 的"单条读写"提升为"目录级状态"：按目录唯一、维护 name → 元数据快照、超出条数时淘汰。

**文件**：`SharedMemoryLoader.kt`、`SharedMemoryLoaderTest.kt`

**spec 依据**：§4.2（权威代码见该节）、§6.2、§6.4。

**步骤**

1. 类骨架：

```kotlin
internal class SharedMemoryLoader private constructor(
    private val memoryStore: SharedMemoryStore,
    private val maxFileCount: Int,
) {
    private val mutex = Mutex()

    @Volatile
    private var snapshot: Map<String, SharedMemory> = emptyMap()

    fun all(): List<SharedMemory> = snapshot.values.toList()
    fun get(name: String): SharedMemory? = snapshot[name]

    suspend fun load(name: String): String? { /* 步骤 2 */ }
    internal suspend fun modify(name: String, transform: suspend (String?) -> String?): String? { /* 步骤 3 */ }
    private suspend fun reload() = mutex.withLock { /* 步骤 4 */ }

    companion object { /* 步骤 5 */ }
}
```

2. `load(name)`：读正文 + 刷新访问时间 + 返回正文：

```kotlin
suspend fun load(name: String): String? {
    var body: String? = null
    memoryStore.modify(name) { current ->
        if (current == null) return@modify null          // 文件不存在：什么也不做
        val text = SharedMemory.bodyOf(current)
        body = text
        SharedMemory.parse(name, current)
            .copy(lastAccessedAt = Instant.now())
            .render(text)
    }
    reload()   // 必须在 store.modify 返回之后
    return body
}
```

`load` **不判淘汰**；它把 `lastAccessedAt` 刷成 now，等于让本条目在随后的 `reload` 里排到淘汰队尾。注意它会写盘（mtime 变化是预期行为，见 spec §8.1 第 9 条）。

3. `modify(name, transform)`：一行委托 + 自动 reload：

```kotlin
internal suspend fun modify(name: String, transform: suspend (current: String?) -> String?): String? {
    val result = memoryStore.modify(name, transform)
    reload()
    return result
}
```

这是**写入路径的唯一入口**：插件初始化 `user.md`、save 工具写记忆，全部经此。写完顺手重建快照放在这里，调用方不可能漏掉。

4. `reload()`：扫目录 → 超额则淘汰 → 整体替换快照：

```kotlin
private suspend fun reload(): Unit = mutex.withLock {
    val metas = LinkedHashMap<String, SharedMemory>()
    for (name in memoryStore.list()) {
        val content = runCatching { memoryStore.read(name) }.getOrNull() ?: continue  // 坏文件跳过
        metas[name] = SharedMemory.parse(name, content)
    }

    if (metas.size <= maxFileCount) {     // 常态：没超额，一条不删
        snapshot = metas
        return@withLock
    }

    val evictOrder = metas.values.sortedWith(compareBy({ it.lastAccessedAt }, { it.name }))
    for (candidate in evictOrder) {
        if (metas.size <= maxFileCount) break
        val observed = candidate.lastAccessedAt
        var removed = false
        memoryStore.modify(candidate.name) { current ->
            if (current == null) return@modify null
            val latest = SharedMemory.parse(candidate.name, current)
            // 被并发 touch 过：放过它
            if (latest.lastAccessedAt.isAfter(observed)) return@modify current
            removed = true
            null                                   // 这里的 null 真的是"删文件"
        }
        if (removed) metas.remove(candidate.name)
    }

    snapshot = metas
}
```

要点：

- 淘汰排序平局按 `name` 升序，让测试可断言。
- **删到刚好回到上限就停**：写第 N+1 条只带走一条；只有目录被外部塞进多个文件时才会一次删多条。
- `user.md` 不在 `list()` 里，天然不占名额、不被淘汰。
- 二次判定的加锁必须发生在外层 `store.modify` 已经返回之后（就是这里的调用位置），**不要**把它挪进 `transform`。

5. 伴生对象：按目录单例 + 测试缝：

```kotlin
companion object {
    private val instances = ConcurrentHashMap<String, SharedMemoryLoader>()

    internal fun of(directory: Path, maxFileCount: Int): SharedMemoryLoader {
        Files.createDirectories(directory)
        val real = directory.toRealPath()
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

    internal fun create(memoryStore: SharedMemoryStore, maxFileCount: Int): SharedMemoryLoader =
        SharedMemoryLoader(memoryStore, maxFileCount)
}
```

- key 用 `toRealPath()`：否则 `./mem` 与 `/abs/mem` 会各持一套锁和快照。
- 用 `compute` 而不是 `get` + `computeIfAbsent`：检查上限与注册必须原子。
- 构造器 `private`：让"按目录唯一"成为编译期事实；测试只能走 `create`。

6. 写 `SharedMemoryLoaderTest`（用 `create(FakeSharedMemoryStore(), n)`，不碰磁盘）：

| 用例 | 断言 |
|---|---|
| 新建 → 快照同步 | `modify` 返回后 `get(name)` 立即可命中，无需手工 reload |
| `load` 刷新时间 | 文件里 `lastAccessedAt` 变新，正文原样返回 |
| `load` 不存在的 name | 返回 `null`，且**不**创建空文件 |
| `load` 不判淘汰 | 超额状态下 load 一条，文件仍在（淘汰只由 reload 针对其他条目） |
| 条数未超额 | 写 3 条、上限 5 → 一条都没删 |
| 超额淘汰 | 上限 2，依次写 A、B、C → A 被删，B/C 保留 |
| 平局排序 | 两条 `lastAccessedAt` 相同 → 删 `name` 字典序小的那个 |
| 一次删到刚好 | 上限 2，预置 5 个文件 → 一次 reload 后恰好剩 2 条 |
| 不会删空 | 上限 1，写 3 条 → 恰好剩 1 条 |
| 并发 touch 的候选被跳过 | 淘汰循环里先 touch 候选再触发 reload（可用 fake 的 read 钩子模拟） |
| 坏文件容错 | `failOnRead` 标记一个 name → reload 不抛异常，该条不进快照也不被删 |
| `of` 复用实例 | 同一目录两次 `of` 返回 `===` 的同一对象 |
| `of` 路径归一 | `dir` 与 `dir/../dir` 归一到同一 key |
| `of` 上限冲突 | 同目录不同 `maxFileCount` → `IllegalArgumentException` |
| `user` 不占名额 | fake 里放 `user` + 2 条普通记忆，上限 2 → `user` 不参与淘汰且仍在 |
| `create` 与注册表无关 | `create` 出的实例不影响 `of` 的注册表（测试之间用不同 TempDir/不同 fake） |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryLoaderTest'
```

**完成标志**：上述用例全绿；代码里不存在 `reload()` 的 public 引用（`grep -r "reload()" ` 只应出现在 `load` / `modify` 内部）。

---

## Task 5: `SharedMemoryLoadTool` —— 读出工具

**目标**：让 LLM 既能"看到有哪些记忆"（description 里的清单），又能按 name 取回正文。刷新 `lastAccessedAt` 由 `loader.load` 负责，工具只管转发。

**文件**：`SharedMemoryLoadTool.kt`、`SharedMemoryLoadToolTest.kt`

**spec 依据**：§4.4（权威代码见该节）、§6.1（保留名拦截）。

**步骤**

1. 类骨架：

```kotlin
internal class SharedMemoryLoadTool(
    private val loader: SharedMemoryLoader,
) : Tool {
    override val name: String = "load_shared_memory"

    override val description: String
        get() = /* 步骤 2 */

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionResult = /* 步骤 3 */
}
```

2. `description` 用 **getter**，不要 `by lazy`、不要 `private val desc = ...`：

```kotlin
override val description: String
    get() = buildString {
        appendLine("加载共享记忆。")
        appendLine("当前可用的记忆文件:")
        val memories = loader.all()          // 只取一次:判空与遍历必须是同一份快照
        if (memories.isEmpty()) {
            appendLine("(暂无)")
        } else {
            memories.forEach { m -> appendLine("- ${m.name}：${m.description}") }
        }
        appendLine("传入 name 调用,记忆按需加载入上下文。")
    }.trimEnd()
```

每轮 `ToolRegistry.all().map { it.toDefinition() }` 都会读这个 getter，所以 `reload` 之后下一轮 LLM 自动看到新记忆。写成 `by lazy` 会让清单永远停在第一次求值的那一刻——这是本 Task 唯一的"看起来能跑、实际致命"的坑。

3. `execute`：

```kotlin
override suspend fun execute(
    arguments: JsonElement,
    context: ToolExecutionContext,
): ToolExecutionResult {
    val name = (arguments.jsonObject["name"] as? JsonPrimitive)?.content
        ?: return ToolExecutionResult.error("Missing 'name'")

    // 保留名拦截:user.md 不在 list() 里,但 loader.load 直读文件、不查快照,
    // 只靠快照排除挡不住它,必须在工具层显式拒绝
    if (name == SharedMemory.RESERVED_USER) {
        return ToolExecutionResult.error(
            "'${SharedMemory.RESERVED_USER}' is the auto-injected user profile and is not in the memory list; " +
                "its content is already in your system prompt. Use save_shared_memory to update it."
        )
    }

    val content = loader.load(name)
        ?: return ToolExecutionResult.error("Memory not found: $name (it may have been forgotten)")
    return ToolExecutionResult.success(content)
}
```

要点：

- **这里不做 `MEMORY_NAME_PATTERN` 校验**（save 工具才做）：非法 name 会让 `PathSharedMemoryStore.fileOf` 的 `require` 抛异常，`ToolRegistry.dispatch` 兜底回显 `arguments`——而 load 的 `arguments` 只有 name 字段，回显代价可忽略。save 必须提前校验是因为它的 `arguments` 里带着整篇正文，回显等于把刚写的正文再灌一遍上下文。
- `context` 参数用不到，但保留完整签名（`Tool` 接口要求），不要改名成 `_` 以外的花样。
- 返回的是**正文**（不含 frontmatter）。`loader.load` 已经做了 `bodyOf`，工具不要再切一次。

4. 写 `SharedMemoryLoadToolTest`（构造 `create(FakeSharedMemoryStore(), n)` + 预置文件）：

| 用例 | 断言 |
|---|---|
| description 列出全部记忆 | 快照有 `travel` / `food` → 文本同时含 `- travel：` 与 `- food：`，且 description 段确实出现 |
| description 每轮刷新 | 先取一次 description，再写入 `new-one`，再取一次 → 第二次含 `new-one`（证明是 getter） |
| description 空清单 | 无记忆 → 含 `(暂无)` 且不含 `- ` 列表行 |
| 正常 load | `execute({name: travel})` → success，内容等于正文，不含 `---` |
| load 刷新访问时间 | 执行后从 store 读回文件，`lastAccessedAt` 晚于执行前 |
| 保留名拦截 | `name=user` → isError，且 error 文案与"不在清单"相关；即使 `user.md` 存在也不返回其正文 |
| 不存在的 name | `name=ghost` → isError 且含 `not found` |
| 缺参数 | 空 JSON → isError 且含 `Missing 'name'` |
| 即将被淘汰的仍可读 | 上限 1、两条记忆 → load 较旧的那条仍返回正文（淘汰只发生在随后的 reload，且针对其他条目） |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryLoadToolTest'
```

**完成标志**：上述用例全绿；把 description 临时改成 `by lazy` 时"每轮刷新"用例必须转红（写完顺手验证一次，然后改回）。

---

## Task 6: `SharedMemorySaveTool` —— 写入工具（条目级 + 压缩）

**目标**：一个工具承担新建、追加、整条替换、整条删除、超长压缩、`user` 画像写回这六件事。这是全模块唯一有真实分支的逻辑，也是最容易写错的地方。

**文件**：`SharedMemorySaveTool.kt`、`SharedMemorySaveToolTest.kt`

**spec 依据**：§4.5（权威代码见该节）、§5.4、§5.5、§6.3。

**步骤**

1. 文件级常量与构造器：

```kotlin
/**
 * 压缩输入的容错倍数:`newBody` 超过 `maxFileCharSize` 的这个倍数时直接拒绝写入。
 *
 * compressMemory 要把全文塞进 LLM 的 prompt,连输入窗口都装不下的内容压不了;
 * 放行只会让磁盘上多一个永远超长的记忆,之后每次 load 都击穿上下文预算。
 */
private const val MAX_COMPRESS_INPUT_FACTOR = 4

internal class SharedMemorySaveTool(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
    /** user 正文落盘后的回调(插件注入 addInstruction);测试可传 null。 */
    private val onUserProfileUpdated: ((String) -> Unit)? = null,
) : Tool
```

`MAX_COMPRESS_INPUT_FACTOR` 是 `private` 顶层常量，不进任何类（它只服务本文件），也**不要**做成 DSL 参数。

2. `name` / `description` / `parametersSchema`：照 spec §4.5 原文抄。description 是 `val`（常量文本，不含快照），但它是**逐字确定的提示词**，不要自己润色——里面写着 `user` 的存在、`mode` 的两种取值、`old_text` 必须整行一致，这些是模型唯一的操作说明。

```kotlin
override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
    """{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","update"]},"old_text":{"type":"string"}},"required":["name"]}"""
)
```

3. 参数解析（`execute` 的开头）：

```kotlin
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
if (mode == "update" && oldText.isNullOrBlank()) {
    return ToolExecutionResult.error("Missing or empty 'old_text': quote an existing passage verbatim")
}
```

`content` 这里必须在 update 模式兜底成 `""`：下文靠"空串"表示删除条目，不能留成 `null`。

4. 在 `loader.modify` 内按四段写：

```kotlin
val provider = llmProvider ?: context.agentContext.llmProvider
val now = Instant.now()

var notFound = false            // update 未命中 / update 但记忆不存在 / 非法 mode
var missingDescription = false  // 新建记忆但 description 为空
var tooLarge = false            // 超过硬门，拒绝写入
var compressed = false          // 超限且压缩成功
var overLimitKept = false       // 超限但压缩失败，原文照存
var storedBody = ""             // 最终落盘正文（user 刷新用）

loader.modify(name) { current ->
    val meta = if (current == null) {
        if (description.isNullOrBlank()) { missingDescription = true; return@modify current }
        SharedMemory.create(name, description, now)
    } else {
        SharedMemory.parse(name, current)   // description / createdAt 沿用旧值，忽略入参
    }
    val body = current?.let { SharedMemory.bodyOf(it) } ?: ""

    // 失败路径一律 return@modify current:null 表示"删除文件"
    val newBody = when (mode) {
        "append" -> if (body.isBlank()) content else "$body\n$content"
        "update" -> {
            val target = oldText!!.trim()
            val lines = body.lines()
            val index = lines.indexOfFirst { it.trim() == target }
            if (current == null || index < 0) { notFound = true; return@modify current }
            lines.toMutableList().apply {
                if (content.isBlank()) removeAt(index) else this[index] = content
            }.joinToString("\n")
        }
        else -> { notFound = true; return@modify current }
    }

    // 硬门看的是 newBody(含历史正文),不是本次传入的 content
    if (newBody.length > maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR) {
        tooLarge = true
        return@modify current
    }

    val finalBody = if (newBody.length > maxFileCharSize) {
        compressMemory(provider, maxFileCharSize, newBody)?.also { compressed = true }
            ?: newBody.also { overLimitKept = true }
    } else {
        newBody
    }
    storedBody = finalBody
    meta.copy(lastAccessedAt = now).render(finalBody)
}
```

5. `modify` 之后的错误分派（顺序固定为超长 → 缺描述 → 未命中，与 spec 一致）：

```kotlin
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
```

6. user 回调与回执：

```kotlin
// 压缩会改写正文，所以必须用落盘后的 storedBody，而不是调用方传入的 content
if (name == SharedMemory.RESERVED_USER) onUserProfileUpdated?.invoke(storedBody)

val note = when {
    compressed -> " Content was compressed to fit maxFileCharSize; the stored text is a rewritten summary, not what you sent."
    overLimitKept -> " WARNING: content exceeds maxFileCharSize and compression failed; it was stored as-is."
    else -> ""
}
return ToolExecutionResult.success("Saved memory '$name'.$note")
```

7. `compressMemory` 私有方法照 spec §4.5 抄（prompt 文案、`ChatRequest`、`summary.length < body.length` 的无效兜底三者都不能改）。

**陷阱清单**（每条都能单独把功能写坏）

1. **失败路径写成 `return@modify null`** → 文件被删。四条路径（缺 description、update 未命中、非法 mode、超硬门）全部 `return@modify current`。
2. **硬门拿 `content` 比长度** → `append` 时历史正文不算，超长文件照样落盘。必须比 `newBody`。
3. **`compressed` 标记写在 `?:` 分支外** → 压缩失败也会回执"已压缩"。标记只能写在 `?.also {}` 里。
4. **`storedBody` 取 `content`** → user 刷新把未压缩的原文推回 prompt，下一次 update 的 `old_text` 在磁盘上找不到。
5. **`onUserProfileUpdated` 放在 `when` 之前** → 失败路径也刷新 persona（把一条根本没写成功的内容当成画像）。它必须在三个 error 分支之后。
6. **update 用 `body.replace(target, content)`**：删除时只清内容、留下空行（还要单独处理尾行无换行），且重复行会被全部替换成同一个新条目。必须走 `indexOfFirst` + `removeAt`/`set`。
7. **`while (true)` 式重复替换** → 违反"天然只命中一条"。`indexOfFirst` 已保证只碰最上面那行。
8. **忘了 `content.isBlank()` 而要 `isEmpty()`** → 模型传 `"  "` 时会在正文里留一条空白行。
9. **在 `transform` 里调 `loader.modify` 或 `reload`** → 同一把不可重入 `Mutex` 自锁。
10. **`compressMemory` 用字节数判长度** → 中文条目永远不触发压缩。`maxFileCharSize` 与 `newBody.length` 都是字符口径。

**测试表**（全部用 `create(FakeSharedMemoryStore(), n)`；压缩用 `FakeLlmProvider` 脚本一条短响应）

| 用例 | 断言 |
|---|---|
| 新建 append | 磁盘出现该文件，frontmatter 4 字段齐全，正文 = content |
| 新建缺 description | isError 且含 `Missing 'description'`，**不落盘** |
| 已有记忆 append | 正文变成 `旧行\n新行`，description / createdAt 不变 |
| 已有记忆忽略入参 description | 传新 description 后文件里仍是旧值 |
| update 整行替换 | 命中行被替换，其余行与顺序不变 |
| update 空 content 删行 | 该行消失且**不留空行**，行数 -1 |
| update 未命中 | isError 且含 `old_text not found`，文件字节不变 |
| update 的 name 不存在 | isError，**不新建文件**；带 description 时走 `old_text not found`（建 meta → 空正文 → 无行可命中），不带时 meta 段先报 `Missing 'description'`（§4.5 的判定顺序如此，两分支都不落盘） |
| update 空 / 全空白 old_text | isError，不落盘 |
| update 半行引用 | 只引用了某行的前半句 → isError，不落盘 |
| 重复行只改第一条 | 正文两行完全相同 → 只替换上面那行，下面那行原样 |
| 特殊字符字面量 | `old_text` 含 `$`、`\`、`.`、`(` → 按字面量逐行比较，不被当正则 |
| 非法 name | `../x`、`a/b`、65 字符、空串 → isError，`.tmp` 与目标文件都不产生 |
| 非法 mode | `mode="insert"` → isError，不落盘 |
| 写入即访问 | 保存后文件 `lastAccessedAt` 比保存前新 |
| 新建后快照立即可见 | `execute` 返回后 `loader.get(name)` 命中（证明 `loader.modify` 内部 reload 生效） |
| 超过 maxFileCharSize 触发压缩 | `maxFileCharSize=100`、content 200 字符、脚本返回 50 字符 → 文件正文 = 脚本响应，回执含 `compressed` |
| 压缩结果没变短 | 脚本返回 200 字符 → 原文照存，回执含 `WARNING` |
| 压缩 LLM 抛错 | provider 内 `throw` → 原文照存 + WARNING，`execute` 不抛异常 |
| 超硬门 | `newBody.length > 4 × maxFileCharSize` → isError 且不落盘；边界值 `== 4 ×` 允许写入并进入压缩 |
| 硬门按 newBody 算 | 已有 380 字正文 + append 30 字、`maxFileCharSize=100` → 命中硬门（不是按 30 字放行） |
| 回执不含字符数 | success 文本里没有 `100 chars` / `length=` 之类内部指标 |
| save 后条数超额 | 上限 2，写 A、B、C → A 被删（loader 内 reload 生效） |
| `onUserProfileUpdated` 正常 | `name=user` 且成功 → 回调恰好一次，参数 = 落盘正文 |
| `onUserProfileUpdated` 失败路径 | 缺 description / 超硬门 / update 未命中 → 一次都不调 |
| `onUserProfileUpdated` 其他 name | `name=travel` 成功 → 一次都不调 |
| `onUserProfileUpdated` 为 null | 一切照旧，不 NPE |
| 压缩后回调传压缩正文 | `name=user` 且触发压缩 → 回调参数 = 脚本响应而非入参 content |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemorySaveToolTest'
```

**完成标志**：上述用例全绿。特别注意"文件字节不变"类断言要用 `store.read` 前后对比，只断言 `isError` 不足以证明没落盘（`null` 误用会删文件，反而也返回 success）。

---

## Task 7: `SharedMemoryPlugin` + `AgentBuilder.sharedMemory`

**目标**：把 loader、两个工具、`user.md` 初始化与 persona 刷新接成一个插件，并暴露唯一公开 API。

**文件**：`SharedMemoryPlugin.kt`、`SharedMemoryExtensions.kt`、`SharedMemoryPluginTest.kt`

**spec 依据**：§4.3、§4.7、§6.1。

**步骤**

1. `SharedMemoryPlugin.kt`：

```kotlin
internal object SharedMemoryPluginConfig

internal class SharedMemoryPlugin(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) { /* no-op */ }

    override fun install(context: AgentPluginContext) {
        // user 的刷新入口:标签固定 "user-profile",addInstruction 遇同名段落即原位替换
        val refreshUserProfile: (String) -> Unit = { newBody ->
            context.addInstruction(newBody, "user-profile")
        }

        val userBody = runBlocking { ensureUserMemory() }   // loader.modify 内部已自动 reload
        if (userBody.isNotBlank()) refreshUserProfile(userBody)

        context.registerTool(SharedMemoryLoadTool(loader))
        context.registerTool(
            SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile)
        )
    }

    private suspend fun ensureUserMemory(): String {
        val empty = SharedMemory.create(
            name = SharedMemory.RESERVED_USER,
            description = "用户画像,每轮主动注入 system prompt",
            now = Instant.now(),
        )
        val content = loader.modify(SharedMemory.RESERVED_USER) { current ->
            current ?: empty.render(body = "")
        } ?: return ""
        return SharedMemory.bodyOf(content)
    }
}
```

2. `SharedMemoryExtensions.kt`：

```kotlin
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

要点：

- **首次注入与运行期刷新必须走同一个 `refreshUserProfile`**，不要另写一行 `context.addInstruction(userBody, "...")`——两处 label 一旦写岔（比如一处 `user-profile`、一处 `user`），persona 里就会出现两个 user 段落，且后续只替换其中一个。
- `"user-profile"` 是内联字面量。不要为它定义常量（禁令表已把 label 常量列为删除项）。
- 正文为空时**不注入**（`isNotBlank()` 判断）；但运行期被清空时要**注入空串**（见 Task 6），两者不对称是有意的：安装时注入空段落毫无信息量，运行时跳过刷新会让模型一直看着过期画像。
- `configure` 是 no-op：所有配置在构造器完成，`SharedMemoryPluginConfig` 只用于满足 `C : Any`。
- `runBlocking` 只允许出现在 `install` 这一处。`install` 是同步签名而 store 是 suspend，阻塞点是 Agent 构建期，不在推理路径上。
- DSL 的默认值只写在这里：`SharedMemoryLoader.of` / `SharedMemoryPlugin` 都不给默认值，避免第二处默认值。
- `require` 的两条校验必须在 `of(...)` **之前**：`of` 有一处副作用（`Files.createDirectories`），参数非法时不应该先建目录。
- 别把扩展函数命名成 `memory(...)`：`AgentBuilder` 已有成员 `fun memory(memory: Memory, maxRounds: Int = 20)`（短期会话历史），同名会造成两个语义无关的入口。

3. 写 `SharedMemoryPluginTest`。用与 `agent/skill` 的 `SkillPluginTest` 同款的 fake context，但 `addInstruction` 要落到真的 `AgentPersona` 上，才能断言"原位替换"：

```kotlin
private class FakePluginContext : AgentPluginContext {
    val tools = mutableListOf<Tool>()
    val persona = AgentPersona()
    override fun registerTool(tool: Tool) { tools += tool }
    override fun addInstruction(content: String, label: String?) { persona.extra(content, label) }
}
```

| 用例 | 断言 |
|---|---|
| install 注册两个工具 | 工具名恰好含 `load_shared_memory` 与 `save_shared_memory`，且没有别的 |
| user.md 不存在 | 创建成功，正文为空串，frontmatter 的 name = `user` |
| user.md 已存在 | 预置 `喜欢海鲜` → install 后正文不变（不覆盖） |
| 正文为空不注入 | 新目录 install 后 `persona.toString()` 不含 `<user-profile>` |
| 正文非空注入一次 | 预置正文 → persona 含 `<user-profile>` 且出现次数 = 1，内容为正文 |
| 运行期刷新 | 拿 install 时注册的 save 工具，`save("user", "喜欢日料")` 后 persona 文本立即变化，且 `<user-profile>` 仍只有 1 个 |
| 刷新不累积 | 连续两次 save("user") → `<user-profile>` 计数仍为 1（不是 2） |
| 其他记忆不刷新 | save("travel") 后 persona 文本与 save 前逐字相同 |
| 压缩时刷新用压缩正文 | 触发压缩的 save("user") → persona 里是脚本返回的短文本 |
| DSL 参数校验 | `maxFileCount = 0`、`maxFileCharSize = 0` 分别抛 `IllegalArgumentException`，且**不会**先创建目录 |
| DSL 接入 | `AgentBuilder().apply { sharedMemory(dir) }.build()` 后工具列表含两个共享记忆工具（若 `build()` 需要 `llmProvider`，先设 `FakeLlmProvider`） |
| 同目录共享 | 同目录两次 DSL 调用 → 两次 install 的 `loader` 是同一实例（通过工具内行为间接断言：A 写入后 B 的工具 description 能看到） |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryPluginTest'
```

**完成标志**：上述用例全绿；`grep -rn "addInstruction" agent/memory/shared/src/main` 的结果只有 `refreshUserProfile` 一处。

---

## Task 8: 集成测试 —— 从 DSL 到 LLM 工具闭环

**目标**：验证组件之间的接缝：DSL 装进 `AgentBuilder` 后工具真在 registry 里、两轮 LLM 调用能走完 `save → load`、压缩与淘汰在真实 Agent 装配下仍生效。

**文件**：`SharedMemoryIntegrationTest.kt`

**spec 依据**：§7.2、§5.1–§5.5。

**步骤**

1. 装配一个真实 Agent（`build()` 需要 `llmProvider`，用 core testFixtures 的 `FakeLlmProvider` 脚本化响应）：

```kotlin
val provider = FakeLlmProvider(nonStreamResponses = listOf(/* save 轮 */, /* load 轮 */, /* 终答轮 */))
val agent = AgentBuilder().apply {
    llmProvider(provider)
    sharedMemory(tempDir, maxFileCount = 4, maxFileCharSize = 200)
}.build()
```

2. 脚本内容按 `ChatResponse.message.toolCalls` 构造：第一轮返回 `save_shared_memory(name="travel", content="喜欢海边")`，第二轮返回 `load_shared_memory(name="travel")`，第三轮返回纯文本。断言点放在**磁盘与工具返回值**上，不要断言模型输出文本（脚本是写死的，断言它没有信息量）。

| 用例 | 断言 |
|---|---|
| 工具真的注册进 registry | agent 的工具列表含 `load_shared_memory` / `save_shared_memory` |
| save → load 闭环 | 三轮跑完后 `tempDir` 下有 `travel.md`，正文含 `喜欢海边`；第二轮的工具结果文本 = 该正文 |
| user 注入到 system prompt | 预置 `user.md` 正文后跑一轮 → `provider.recordedRequests[0].messages` 里 system 文本含该正文 |
| 运行期刷新 | 第一轮工具调用为 save("user") → 第二个请求的 system 文本已是新正文（同一会话内生效，不用新建 Agent） |
| 压缩在真实装配下生效 | `maxFileCharSize=100`，脚本返回 40 字摘要 → `travel.md` 正文 = 摘要 |
| 条数淘汰 | `maxFileCount=2`，脚本连续 save 三条不同 name → 目录里只剩 2 个 `.md`，最久未访问的被删 |
| 多 Agent 同目录共享 | 两个 `AgentBuilder` 传同一目录 → A 写完后 B 的工具 description 立即列出该记忆 |

**验证**

```bash
./gradlew :agent:memory:shared:test --tests '*SharedMemoryIntegrationTest'
```

**完成标志**：上述用例全绿。跑测试前先确认 `@TempDir` 或 `Files.createTempDirectory` 隔离目录——`SharedMemoryLoader.instances` 是进程级注册表，同一个真实路径会在测试之间串场，且**永远清理不掉**。

---

## Task 9: 全模块校验与（可选）demo

**目标**：收口。确认没有旧结构残留、公开面只有一处、全模块构建通过。

**步骤**

1. 全模块构建（编译 + 全部测试）：

```bash
./gradlew :agent:memory:shared:build
```

2. 扫旧类型名残留（期望输出为空）：

```bash
grep -rnE "SharedMemoryFile|SharedMemoryContext|SharedMemoryRegistry|rescan|forgetDuration|isExpired|maxTotalSize|byteSize|USER_PROFILE_LABEL" \
  agent/memory/shared/src
```

命中说明有实现还停在旧设计上，改掉再提交。**只扫 `src`，不要扫本计划**：上面的"禁止出现的旧结构"表里本来就列了这些名字，扫文档只会扫到自己。

3. 确认公开面只有一个声明：

```bash
grep -rn "^public \|^    public " agent/memory/shared/src/main
```

期望：只有 `SharedMemoryExtensions.kt` 里的 `public fun AgentBuilder.sharedMemory(...)`；其余类全为 `internal`，且 `internal class` 的成员不写多余可见性修饰符（仓库既有风格，见 `agent/skill/.../SkillPlugin.kt`）。

4. 确认没有第二处 `reload()` 调用点：

```bash
grep -rn "reload()" agent/memory/shared/src/main
```

期望：只出现在 `SharedMemoryLoader` 内的 `load` / `modify` 两个方法体里，`reload` 本身仍是 `private`。

5. （可选）`demos/agent` 加共享记忆示例：
   - `demos/agent/build.gradle.kts` 加 `implementation(project(":agent:memory:shared"))`；
   - 在已有的 Agent 装配处加一行 `sharedMemory(Paths.get("memories"))`（默认参数即可）；
   - `demos/agent/README.md` 补一句："记忆目录默认在 demo 工作目录下的 `memories/`，两次运行之间保留；删掉文件即遗忘。"
   - demo 不是完成定义的一部分，可单独一次提交（`docs(demos): shared-memory 示例`）。

**完成标志**：步骤 1 绿，步骤 2/3/4 的输出符合期望。

---

## Self-Review

### spec 覆盖表

| spec 小节 | 落在哪个 Task |
|---|---|
| §3 模块与依赖 | Task 1 |
| §4.1 `SharedMemory`（parse / bodyOf / render / create） | Task 2 |
| §4.2 `SharedMemoryLoader`（load / modify / reload / of / create） | Task 4 |
| §4.3 `SharedMemoryPlugin`（user 初始化 + 写时刷新） | Task 7 |
| §4.4 `SharedMemoryLoadTool` | Task 5 |
| §4.5 `SharedMemorySaveTool`（条目级 + 压缩 + 硬门） | Task 6 |
| §4.6 `SharedMemoryStore` / `PathSharedMemoryStore` / `MEMORY_NAME_PATTERN` | Task 3 |
| §4.7 DSL `AgentBuilder.sharedMemory` | Task 7 |
| §5.1 install 数据流 | Task 7 |
| §5.2 每轮 LLM 请求（description getter） | Task 5 |
| §5.3 load 数据流 | Task 4 + Task 5 |
| §5.4 save 数据流 | Task 6 |
| §5.5 同步压缩 | Task 6（步骤 7） |
| §6.1 `user.md` 的创建 / 读取拦截 / 写入 / 注入刷新 / 淘汰豁免 | Task 2（`RESERVED_USER`）、Task 3（`list` 排除）、Task 5（拦截）、Task 7（注入刷新） |
| §6.2 reload 并发安全 | Task 4（测试表的并发 touch 用例） |
| §6.3 压缩语义与并发 | Task 6 |
| §6.4 条数淘汰语义 | Task 4 |
| §6.5 与短期 Memory 正交 | 无需代码（不与 `Memory` 交互即成立） |
| §7.1 单元测试 | Task 2–7 各自的测试表 |
| §7.2 集成测试 | Task 8 |
| §7.3 不在测试范围 | 不做 |
| §8.1 当前限制 | 不写代码；§8.1 第 9/10 条是**约束**：不要把记忆文件当人工入口、不要在淘汰时清 `locks` |
| §8.2 未来扩展 | 不做 |
| §9 实现顺序 | Task 1–9 |

### 占位符扫描

本计划里出现的 `/* 步骤 N */` 只存在于骨架代码，实现时必须展开。除骨架以外的代码块都是可直接落地的片段，不存在 `TODO` / `...` / "自行补充"。

### 类型与命名一致性

实现时以下名字必须全局一致（拼错即编译不过或测试串味）：

- 类型：`SharedMemory` / `SharedMemoryStore` / `PathSharedMemoryStore` / `SharedMemoryLoader` / `SharedMemoryLoadTool` / `SharedMemorySaveTool` / `SharedMemoryPlugin` / `SharedMemoryPluginConfig`
- 顶层声明：`MEMORY_NAME_PATTERN`（`SharedMemoryStore.kt`）、`MAX_COMPRESS_INPUT_FACTOR`（`SharedMemorySaveTool.kt`，`private`）
- 成员：`render(body)` / `bodyOf(content)` / `parse(name, content)` / `create(name, description, now)` / `RESERVED_USER`；`copy` / `equals` / `hashCode` 由 `data class` 生成
- Loader：`all()` / `get(name)` / `load(name)` / `modify(name, transform)` / `reload()` / `of(directory, maxFileCount)` / `create(memoryStore, maxFileCount)`
- 工具名：`load_shared_memory` / `save_shared_memory`；工具参数：`name` / `description` / `content` / `mode` / `old_text`
- persona label：`"user-profile"`（字面量，无常量）
- 插件 id：`"shared-memory"`

### 已知偏离

无。spec 与本计划冲突时以 spec 为准；若实现中发现 spec 本身有误，先改 spec 再改计划，不要只在代码里"就地修正"。

---

## Execution Handoff

**执行顺序**：Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9，严格串行。Task 2–4 是地基（值对象 → 存储 → 编排），任何一步没绿都不要开始写工具。

**每个 Task 的收尾动作**：

1. 跑该 Task 的验证命令，确认全绿；
2. `git status` 确认改动只落在该 Task 声明的文件里；
3. 提交，提交信息用中文正文，例如：

```text
feat(memory-shared): 新增 shared-memory 模块骨架
feat(memory-shared): SharedMemory 值对象与 md 编解码
feat(memory-shared): PathSharedMemoryStore 的原子写入与 per-name 锁
feat(memory-shared): SharedMemoryLoader 的条数淘汰与快照
feat(memory-shared): load_shared_memory 读出工具
feat(memory-shared): save_shared_memory 的条目级写入与同步压缩
feat(memory-shared): 插件接线与 AgentBuilder.sharedMemory
test(memory-shared): 从 DSL 到 LLM 的工具闭环
```

**卡住时按这个顺序自查**，不要先怀疑别人：

1. 文件被删了 → 检查失败路径是不是 `return@modify null`；
2. 同名并发写互相覆盖 → 检查锁是不是 `kotlinx.coroutines.sync.Mutex`，以及 `locks` 是不是被清过；
3. 卡死不返回 → 检查 `reload()` 是否被放进了 `transform`；
4. 清单一直是旧的 → 检查 `description` 是不是写成了 `by lazy`；
5. persona 里出现两个 user 段落 → 检查 `addInstruction` 的 label 是否两处不一致；
6. 中文内容不触发压缩 → 检查是不是用字节数判长度；
7. 目录里出现 `.md.tmp` → 检查 `ATOMIC_MOVE` 是否漏了。

**不属于本计划范围**：跨进程协调、后台巡检、把 `reload()` 提为 public、`store` 注入到 DSL、Android 平台适配。这些都在 spec §8 里明确记录了取舍，不要在执行中顺手加。
