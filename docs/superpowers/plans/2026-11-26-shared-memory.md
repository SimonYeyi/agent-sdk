# 共享记忆插件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `:agent:memory:shared` 模块,提供 LLM 可自主读写的 markdown 共享记忆能力,与 core 的短期会话 Memory 正交,含遗忘与压缩机制。

**Architecture:** 复用 `Capability` 框架,`load_shared_memory` 走委托模式继承 `CapabilityLoadTool`,`save_shared_memory` 作为 auxiliary tool;`SharedMemoryPlugin` 直接实现 `AgentPlugin<SharedMemoryPluginConfig>`(因 `CapabilityPlugin.install` 是 final 且不支持 `appendInstructions`),内部手工调 `CapabilityAdapter.of` 拼装 load 工具。`CapabilityLoadTool` 的 `description` / `parametersSchema` 从 `by lazy` 改为 getter,使重扫后下一轮 LLM 自动看到新话题。

**Tech Stack:** Kotlin 2.4 (JVM 21) · kotlinx.serialization.json · kotlinx.coroutines · java.nio.file.Path · java.time.Instant/Duration · JUnit 4 + kotlin-test

## Global Constraints

- 包名:`io.github.yeyi.agent.memory.shared`(避开 core 的 `io.github.yeyi.agent.memory`)
- 模块路径:`agent/memory/shared/`,Gradle 子模块名 `:agent:memory:shared`
- 时间 API:统一用 `java.time.Instant` / `java.time.Duration`,不用 `kotlin.time.Duration`
- frontmatter 解析:不引入 YAML 库,自写最小化解析(只支持 `---` + `key: value` 行)
- IO 抽象:`SharedMemoryStore` 接口 + `PathSharedMemoryStore` 默认实现,IO 走 `Dispatchers.IO`
- 提交规范:遵循 `.claude/rules/git_commit.md`,`chore(build)` / `feat(shared-memory)` / `test(shared-memory)` / `refactor(capability)` 等,**只提交不推送**
- API 形态:`explicitApi()` 严格模式,public API 显式标注
- 测试:JUnit 4 + kotlin-test,`runTest` 跑协程测试
- 配置参数命名:`memoryFileLimit`(单文件字符上限)/ `forgetDuration`(遗忘时间,java.time.Duration)

---

## File Structure

```
agent/memory/shared/
├── build.gradle.kts                                    # Task 2 创建
└── src
    ├── main/kotlin/io/github/yeyi/agent/memory/shared/
    │   ├── SharedMemoryFile.kt           # Task 3:frontmatter 解析/序列化
    │   ├── SharedMemoryStore.kt          # Task 4:存储抽象 + PathSharedMemoryStore
    │   ├── SharedMemory.kt       # Task 5:Capability 接口 + DefaultSharedMemory 实现
    │   ├── SharedMemoryContext.kt        # Task 6:Context + Factory
    │   ├── SharedMemoryRegistry.kt       # Task 7:Registry + rescan
    │   ├── SaveSharedMemoryTool.kt       # Task 8:写入工具(append/update);Task 10 加同步压缩;Task 11 加遗忘检查
    │   ├── SharedMemoryPlugin.kt         # Task 9:Plugin 接线 + user.md 初始化
    │   └── SharedMemoryExtensions.kt     # Task 9:AgentBuilder.sharedMemory DSL
    └── test/kotlin/io/github/yeyi/agent/memory/shared/
        ├── SharedMemoryFileTest.kt
        ├── PathSharedMemoryStoreTest.kt
        ├── SharedMemoryTest.kt
        ├── SharedMemoryRegistryTest.kt
        ├── SaveSharedMemoryToolTest.kt
        ├── SharedMemoryPluginTest.kt
        └── SharedMemoryIntegrationTest.kt

agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/
└── CapabilityLoadTool.kt           # Task 1:改 lazy → getter
```

`settings.gradle.kts` 需新增 `include(":agent:memory:shared")`。

---

## Task 1: CapabilityLoadTool 改 lazy → getter

**Files:**
- Modify: `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityLoadTool.kt`
- Test: `agent/capability/src/test/kotlin/io/github/yeyi/agent/capability/CapabilityPluginTest.kt`(已有,新增动态 description 测试)

**Interfaces:**
- Consumes: `CapabilityRegistry<C, T, Ctx>` 的 `all()` 方法(已存在)
- Produces: `CapabilityLoadTool.description` / `parametersSchema` 改为每轮求值,新增 capability 后下一轮自动反映

- [ ] **Step 1: 写失败测试 — 新增 capability 后 description 反映新条目**

在 `CapabilityPluginTest.kt` 末尾追加:

```kotlin
@Test
fun `load tool description reflects newly registered capability without re-instantiation`() {
    val registry = DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub")
        .apply { register(StubCapability("alpha", "desc alpha")) }
    val installer = object : CapabilityPlugin<StubCapability, Unit, StubContext>(registry, true) {
        override fun contextFactory() = StubContextFactory()
        override fun arguments(): CapabilityArguments<Unit>? = null
    }
    val context = FakePluginContext().also { installer.install(it) }
    val loadTool = context.tools.first { it.name == "load_stub" }

    // 初始 description 含 alpha
    assertTrue(loadTool.description.contains("alpha"), "initial description should contain alpha")

    // 注册新 capability 后,不重新 install,直接读 description 应含 beta
    registry.register(StubCapability("beta", "desc beta"))
    assertTrue(loadTool.description.contains("beta"), "description should reflect beta after register without re-install")
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:capability:test --tests "io.github.yeyi.agent.capability.CapabilityPluginTest.load tool description reflects newly registered capability without re-instantiation"`
Expected: FAIL — `description should reflect beta after register without re-install` 断言失败(因为 `by lazy` 缓存了首次值)

- [ ] **Step 3: 改 CapabilityLoadTool 的 description / parametersSchema 为 getter**

修改 `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityLoadTool.kt`:

把 `override val description: String by lazy { ... }` 改为:

```kotlin
override val description: String
    get() = buildString {
        appendLine("当以下 $capabilityType 适用于本次任务时，调用本工具以激活使用：")
        registry.all().forEach { appendLine("- ${it.name}：${it.description}") }
        appendLine("如果本次任务需要多个 $capabilityType 并行处理，可以生成多个工具调用。")
    }
```

把 `override val parametersSchema: ToolParameters by lazy { ... }` 改为 `override val parametersSchema: ToolParameters get() { ... }`,内部逻辑原样保留:

```kotlin
override val parametersSchema: ToolParameters
    get() {
        val properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string") })
            if (arguments != null) {
                put("arguments", Json.parseToJsonElement(arguments.schema))
            }
        }
        val required = buildJsonArray {
            add(JsonPrimitive("name"))
            if (arguments != null) add(JsonPrimitive("arguments"))
        }
        return ToolParameters.JsonSchema(
            Json.encodeToString(buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", required)
            })
        )
    }
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:capability:test`
Expected: PASS — 包括新增测试和现有所有 capability 测试

- [ ] **Step 5: 跑 skill / subagent / toolset 模块测试确认无回归**

Run: `./gradlew :agent:skill:test :agent:subagent:test :agent:toolset:test`
Expected: PASS — 行为变化(下一轮自动生效)不影响现有测试

- [ ] **Step 6: 提交**

```bash
git add agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityLoadTool.kt \
        agent/capability/src/test/kotlin/io/github/yeyi/agent/capability/CapabilityPluginTest.kt
git commit -m "refactor(capability): CapabilityLoadTool 的 description/parametersSchema 改为每轮求值

让重扫后下一轮 LLM 自动看到新 capability,不需要重新 install。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: 新建 :agent:memory:shared 模块骨架

**Files:**
- Create: `agent/memory/shared/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Test: 跑 `./gradlew :agent:memory:shared:help` 验证模块被识别

**Interfaces:**
- Consumes: 无(空模块)
- Produces: 一个可被 Gradle 识别的空模块,后续 task 填充内容

- [ ] **Step 1: 在 settings.gradle.kts 注册新模块**

修改 `settings.gradle.kts`,在 `include(":agent:skill")` 之后追加:

```kotlin
include(":agent:memory:shared")
```

- [ ] **Step 2: 创建 build.gradle.kts**

新建 `agent/memory/shared/build.gradle.kts`,内容仿照 `agent/skill/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":agent:core"))
    api(project(":agent:capability"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
```

- [ ] **Step 3: 创建源目录占位**

```bash
mkdir -p agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory.shared
mkdir -p agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory.shared
```

在 `src/main/kotlin/io/github/yeyi/agent/memory/shared/` 下创建占位文件 `package.txt`(空),让 Gradle 不报"无源文件":

```bash
touch agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/package.txt
```

- [ ] **Step 4: 验证模块被 Gradle 识别**

Run: `./gradlew :agent:memory:shared:help`
Expected: PASS — 输出 help 信息,无错误

- [ ] **Step 5: 提交**

```bash
git add settings.gradle.kts agent/memory/shared/
git commit -m "chore(shared-memory): 新建模块骨架

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: SharedMemoryFile —— frontmatter 解析/序列化

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFile.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFileTest.kt`

**Interfaces:**
- Consumes: `java.time.Instant`
- Produces:
  - `public data class SharedMemoryFile(val frontmatter: Frontmatter, val body: String)`
  - `public data class SharedMemoryFile.Frontmatter(val name: String, val description: String, val createdAt: Instant, val lastAccessedAt: Instant)`
  - `public companion object { fun parse(content: String): SharedMemoryFile; fun serialize(frontmatter: Frontmatter, body: String): String }`

- [ ] **Step 1: 写失败测试 — 往返序列化**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFileTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedMemoryFileTest {

    @Test
    fun `serialize and parse roundtrip preserves frontmatter and body`() {
        val fm = SharedMemoryFile.Frontmatter(
            name = "travel",
            description = "旅行偏好",
            createdAt = Instant.parse("2026-11-25T10:00:00Z"),
            lastAccessedAt = Instant.parse("2026-11-25T14:00:00Z"),
        )
        val body = "# 旅行偏好\n\n用户偏好海岛游。"
        val serialized = SharedMemoryFile.serialize(fm, body)

        val parsed = SharedMemoryFile.parse(serialized)
        assertEquals(fm, parsed.frontmatter)
        assertEquals(body, parsed.body)
    }

    @Test
    fun `parse extracts frontmatter fields with ISO-8601 instants`() {
        val content = """
            ---
            name: travel
            description: 旅行偏好
            createdAt: 2026-11-25T10:00:00Z
            lastAccessedAt: 2026-11-25T14:00:00Z
            ---

            # 旅行偏好
            用户偏好海岛游。
        """.trimIndent()

        val parsed = SharedMemoryFile.parse(content)
        assertEquals("travel", parsed.frontmatter.name)
        assertEquals("旅行偏好", parsed.frontmatter.description)
        assertEquals(Instant.parse("2026-11-25T10:00:00Z"), parsed.frontmatter.createdAt)
        assertEquals(Instant.parse("2026-11-25T14:00:00Z"), parsed.frontmatter.lastAccessedAt)
        assertEquals("# 旅行偏好\n用户偏好海岛游。", parsed.body)
    }

    @Test
    fun `parse handles empty body`() {
        val content = """
            ---
            name: user
            description: 用户画像
            createdAt: 2026-11-25T10:00:00Z
            lastAccessedAt: 2026-11-25T10:00:00Z
            ---
        """.trimIndent()

        val parsed = SharedMemoryFile.parse(content)
        assertEquals("user", parsed.frontmatter.name)
        assertEquals("", parsed.body)
    }

    @Test
    fun `serialize empty body produces frontmatter only`() {
        val fm = SharedMemoryFile.Frontmatter(
            name = "user",
            description = "用户画像",
            createdAt = Instant.parse("2026-11-25T10:00:00Z"),
            lastAccessedAt = Instant.parse("2026-11-25T10:00:00Z"),
        )
        val serialized = SharedMemoryFile.serialize(fm, "")
        assertEquals(
            """
            ---
            name: user
            description: 用户画像
            createdAt: 2026-11-25T10:00:00Z
            lastAccessedAt: 2026-11-25T10:00:00Z
            ---

            """.trimIndent(),
            serialized,
        )
    }

    @Test
    fun `parse handles description with colon`() {
        val content = """
            ---
            name: meeting
            description: 会议记录:项目进度
            createdAt: 2026-11-25T10:00:00Z
            lastAccessedAt: 2026-11-25T10:00:00Z
            ---

            body
        """.trimIndent()

        val parsed = SharedMemoryFile.parse(content)
        assertEquals("会议记录:项目进度", parsed.frontmatter.description)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryFileTest"`
Expected: FAIL — `SharedMemoryFile` 未定义

- [ ] **Step 3: 实现 SharedMemoryFile**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFile.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import java.time.Instant

/**
 * 共享记忆 md 文件 —— frontmatter 元数据 + Markdown 正文。
 *
 * 文件格式:
 * ```
 * ---
 * name: <话题名>
 * description: <给 LLM 看的描述>
 * createdAt: <ISO-8601 瞬时>
 * lastAccessedAt: <ISO-8601 瞬时>
 * ---
 *
 * <Markdown 正文>
 * ```
 *
 * frontmatter 解析只支持 `key: value` 行 + `---` 分隔符,不引入 YAML 库。
 * description 中允许冒号(解析时按第一个冒号切分,但 value 不 strip 内部冒号)。
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
        private const val FRONTMATTER_DELIMITER = "---"

        public fun parse(content: String): SharedMemoryFile {
            val lines = content.split("\n")
            require(lines.isNotEmpty() && lines[0].trim() == FRONTMATTER_DELIMITER) {
                "Memory file must start with '$FRONTMATTER_DELIMITER'"
            }

            val fields = mutableMapOf<String, String>()
            var i = 1
            while (i < lines.size && lines[i].trim() != FRONTMATTER_DELIMITER) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim()
                    val value = line.substring(colonIdx + 1).trim()
                    fields[key] = value
                }
                i++
            }
            // i 指向闭合 --- 或末尾
            val bodyStart = if (i < lines.size) i + 1 else lines.size
            // 跳过 body 开头的空行
            var bodyIdx = bodyStart
            while (bodyIdx < lines.size && lines[bodyIdx].isBlank()) bodyIdx++
            val body = lines.subList(bodyIdx, lines.size).joinToString("\n")

            return SharedMemoryFile(
                frontmatter = Frontmatter(
                    name = fields.getValue("name"),
                    description = fields.getValue("description"),
                    createdAt = Instant.parse(fields.getValue("createdAt")),
                    lastAccessedAt = Instant.parse(fields.getValue("lastAccessedAt")),
                ),
                body = body,
            )
        }

        public fun serialize(frontmatter: Frontmatter, body: String): String = buildString {
            appendLine(FRONTMATTER_DELIMITER)
            appendLine("name: ${frontmatter.name}")
            appendLine("description: ${frontmatter.description}")
            appendLine("createdAt: ${frontmatter.createdAt}")
            appendLine("lastAccessedAt: ${frontmatter.lastAccessedAt}")
            appendLine(FRONTMATTER_DELIMITER)
            appendLine()
            append(body)
        }
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryFileTest"`
Expected: PASS — 5 个测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFile.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryFileTest.kt
git commit -m "feat(shared-memory): SharedMemoryFile frontmatter 解析与序列化

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: SharedMemoryStore —— 存储抽象 + PathSharedMemoryStore

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryStore.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/PathSharedMemoryStoreTest.kt`

**Interfaces:**
- Consumes: `java.nio.file.Path`, `kotlinx.coroutines.Dispatchers`
- Produces:
  - `public interface SharedMemoryStore { suspend fun read(name: String): String?; suspend fun write(name: String, content: String); suspend fun delete(name: String); suspend fun exists(name: String): Boolean; suspend fun list(): List<String> }`
  - `public class PathSharedMemoryStore(directory: Path) : SharedMemoryStore`
  - `list()` 排除 `user`(保留文件但不列入)

- [ ] **Step 1: 写失败测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/PathSharedMemoryStoreTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathSharedMemoryStoreTest {

    private val tempDir = Files.createTempDirectory("memory-store-test")
    private val store = PathSharedMemoryStore(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun sampleContent(name: String): String = SharedMemoryFile.serialize(
        SharedMemoryFile.Frontmatter(name, "desc", Instant.parse("2026-11-25T10:00:00Z"), Instant.parse("2026-11-25T10:00:00Z")),
        "body for $name",
    )

    @Test
    fun `write creates file and read returns content`() = runTest {
        store.write("travel", sampleContent("travel"))
        val read = store.read("travel")
        assertEquals(sampleContent("travel"), read)
    }

    @Test
    fun `read returns null for missing file`() = runTest {
        assertNull(store.read("missing"))
    }

    @Test
    fun `exists returns true after write, false before`() = runTest {
        assertFalse(store.exists("topic"))
        store.write("topic", sampleContent("topic"))
        assertTrue(store.exists("topic"))
    }

    @Test
    fun `delete removes file`() = runTest {
        store.write("topic", sampleContent("topic"))
        store.delete("topic")
        assertFalse(store.exists("topic"))
    }

    @Test
    fun `list returns md file names without extension, excludes user`() = runTest {
        store.write("travel", sampleContent("travel"))
        store.write("food", sampleContent("food"))
        store.write("user", sampleContent("user"))
        val names = store.list().toSet()
        assertEquals(setOf("travel", "food"), names)
    }

    @Test
    fun `list returns empty for empty directory`() = runTest {
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `write overwrites existing file`() = runTest {
        store.write("topic", "v1")
        store.write("topic", "v2")
        assertEquals("v2", store.read("topic"))
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.PathSharedMemoryStoreTest"`
Expected: FAIL — `SharedMemoryStore` / `PathSharedMemoryStore` 未定义

- [ ] **Step 3: 实现 SharedMemoryStore + PathSharedMemoryStore**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryStore.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 共享记忆文件存储抽象。默认实现 [PathSharedMemoryStore] 走文件系统,宿主可注入自定义实现。
 *
 * 所有方法为 suspend,允许实现内部走 IO dispatcher。
 * [list] 返回所有 `.md` 文件名(不含后缀),**排除 `user`**(user.md 是特殊保留名,不作为普通话题注册)。
 */
public interface SharedMemoryStore {
    public suspend fun read(name: String): String?
    public suspend fun write(name: String, content: String)
    public suspend fun delete(name: String)
    public suspend fun exists(name: String): Boolean
    public suspend fun list(): List<String>
}

/**
 * 基于 [Path] 的 [SharedMemoryStore] 默认实现。
 *
 * - 文件路径:`$directory/$name.md`
 * - IO 走 [Dispatchers.IO]
 * - 构造时自动创建目录
 * - [list] 排除 `user.md`(保留文件但不作为话题注册)
 */
public class PathSharedMemoryStore(private val directory: Path) : SharedMemoryStore {
    init {
        directory.toFile().mkdirs()
    }

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val file = directory.resolve("$name.md").toFile()
        if (file.exists()) file.readText() else null
    }

    override suspend fun write(name: String, content: String): Unit = withContext(Dispatchers.IO) {
        directory.resolve("$name.md").toFile().writeText(content)
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        directory.resolve("$name.md").toFile().delete()
    }

    override suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        directory.resolve("$name.md").toFile().exists()
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        directory.toFile()
            .listFiles { f -> f.extension == "md" && f.nameWithoutExtension != "user" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.PathSharedMemoryStoreTest"`
Expected: PASS — 7 个测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryStore.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/PathSharedMemoryStoreTest.kt
git commit -m "feat(shared-memory): SharedMemoryStore 抽象与 PathSharedMemoryStore 默认实现

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: SharedMemory —— Capability 接口 + Default 实现

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemory.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryTest.kt`

**Interfaces:**
- Consumes: `Capability<Unit, SharedMemoryContext>`(来自 `:agent:capability`)、`SharedMemoryStore`、`SharedMemoryFile`(Task 3/4)
- Produces:
  - `public interface SharedMemory : Capability<Unit, SharedMemoryContext>`(含 `load()` 抽象方法 + `CAPABILITY_TYPE = "shared_memory"` companion)
  - `internal class DefaultSharedMemory(...)` — 持久化 + lastAccessedAt 更新(暂不含遗忘检查,Task 11 加)

**注意:** 本 task 暂不引入 `SharedMemoryContext`,因为 `SharedMemoryContext` 在 Task 6 创建。本 task 的 `DefaultSharedMemory.load()` 直接用构造器传入的 `SharedMemoryStore`,不依赖 context 字段。`activate` 调 `load()` 时 context 参数忽略。

- [ ] **Step 1: 写失败测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedMemoryTest {

    private val tempDir = Files.createTempDirectory("ltm-test")
    private val store = PathSharedMemoryStore(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun seedMemory(name: String, body: String, lastAccessedAt: Instant = Instant.parse("2026-11-25T10:00:00Z")) {
        val content = SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter(name, "desc for $name", Instant.parse("2026-01-01T00:00:00Z"), lastAccessedAt),
            body,
        )
        store.writeSync(name, content)
    }

    private fun SharedMemoryStore.writeSync(name: String, content: String) {
        kotlinx.coroutines.runBlocking { write(name, content) }
    }

    @Test
    fun `CAPABILITY_TYPE is shared_memory`() {
        assertEquals("shared_memory", SharedMemory.CAPABILITY_TYPE)
    }

    @Test
    fun `load returns full md content and updates lastAccessedAt`() = runTest {
        seedMemory("travel", "去东南亚")
        val before = Instant.now()
        val memory = DefaultSharedMemory("travel", "desc for travel", store)
        val content = memory.load()
        val after = Instant.now()

        // 内容含正文
        assertTrue(content.contains("去东南亚"))
        assertTrue(content.contains("name: travel"))

        // lastAccessedAt 已写回文件,且在 [before, after] 区间
        val raw = store.read("travel")!!
        val parsed = SharedMemoryFile.parse(raw)
        assertTrue(!parsed.frontmatter.lastAccessedAt.isBefore(before))
        assertTrue(!parsed.frontmatter.lastAccessedAt.isAfter(after))
    }

    @Test
    fun `activate delegates to load`() = runTest {
        seedMemory("food", "喜欢吃辣")
        val memory = DefaultSharedMemory("food", "desc for food", store)
        val result = memory.activate(null, stubSharedMemoryContext())
        assertTrue(result.contains("喜欢吃辣"))
    }

    @Test
    fun `name and description come from constructor`() {
        val m = DefaultSharedMemory("topic", "my desc", store)
        assertEquals("topic", m.name)
        assertEquals("my desc", m.description)
    }

    // Stub SharedMemoryContext for tests (real one comes in Task 6)
    private fun stubSharedMemoryContext(): SharedMemoryContext {
        val llmProvider = object : LlmProvider {
            override val name: String = "stub"
            override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
                throw UnsupportedOperationException()
            override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): Flow<io.github.yeyi.agent.llm.ChatResponseEvent> =
                throw UnsupportedOperationException()
        }
        return SharedMemoryContext(
            memoryStore = store,
            llmProvider = llmProvider,
            registry = SharedMemoryRegistry(store, 8000, java.time.Duration.ofDays(30), llmProvider),
            memoryFileLimit = 8000,
            forgetDuration = java.time.Duration.ofDays(30),
        )
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryTest"`
Expected: FAIL — `SharedMemory` / `DefaultSharedMemory` / `SharedMemoryContext` / `SharedMemoryRegistry` 未定义

- [ ] **Step 3: 实现 SharedMemory + DefaultSharedMemory**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemory.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.capability.Capability
import java.time.Instant

/**
 * 一个共享记忆话题 —— 对应根目录下的一个 `.md` 文件。
 *
 * 实现 [Capability] 接口,通过 `CapabilityLoadTool` 暴露为 `load_shared_memory` 工具的可路由条目。
 * LLM 调用 `load_shared_memory(name=...)` 时,框架按 name 路由到对应实例,调 [load] 返回 md 全文。
 *
 * 与 Skill 的对称点:
 * - 都是 `Capability<Unit, Ctx>`,load() 返回文本注入 LLM 上下文
 * - 都是"按需加载",description 告诉 LLM 何时使用,正文不预加载
 *
 * 与 Skill 的区别:
 * - Skill 是静态注册,SharedMemory 由 [SharedMemoryRegistry] 在 install/rescan 时动态构建
 * - load() 触发 lastAccessedAt 更新和遗忘检查
 */
public interface SharedMemory : Capability<Unit, SharedMemoryContext> {
    /**
     * 加载本话题的 md 全文(frontmatter + 正文)。
     *
     * 副作用:
     * - 更新 frontmatter 的 `lastAccessedAt` 并写回文件
     * - (Task 11 加)触发本话题的遗忘检查
     *
     * @return md 全文;若文件已被遗忘机制删除,返回 "(memory expired)" 提示
     */
    override suspend fun load(): String

    override suspend fun activate(arguments: Unit?, context: SharedMemoryContext): String = load()

    public companion object {
        internal const val CAPABILITY_TYPE: String = "shared_memory"
    }
}

/**
 * 默认实现 —— 持有 [memoryStore] 与 [name],[load] 时读文件 + 更新 lastAccessedAt。
 */
internal class DefaultSharedMemory(
    override val name: String,
    override val description: String,
    private val memoryStore: SharedMemoryStore,
) : SharedMemory {
    override suspend fun load(): String {
        val raw = memoryStore.read(name) ?: return "(memory expired)"
        val (frontmatter, body) = SharedMemoryFile.parse(raw)
        val updated = frontmatter.copy(lastAccessedAt = Instant.now())
        memoryStore.write(name, SharedMemoryFile.serialize(updated, body))
        return raw // 返回原始内容(包含旧 frontmatter);LLM 看到的是当时的快照
    }
}
```

**注:** `load()` 返回的是更新前的原始 md 全文(LLM 看到的快照是 load 那一刻的内容),`lastAccessedAt` 更新是写回文件的副作用,不进入返回值。

- [ ] **Step 4: 跑测试验证失败(SharedMemoryContext / SharedMemoryRegistry 还没建)**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryTest"`
Expected: FAIL — 仍是 `SharedMemoryContext` / `SharedMemoryRegistry` 未定义

**这是预期的** — 测试中 stubSharedMemoryContext 引用了 Task 6 才会创建的 `SharedMemoryContext` 和 `SharedMemoryRegistry`。先继续 Task 6,完成后回到本 task 跑测试。

- [ ] **Step 5: 暂不提交(等 Task 6 完成后合并提交)**

---

## Task 6: SharedMemoryContext —— 执行上下文 + Factory

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryContext.kt`
- Test: 本 task 不单独写测试,在 Task 5 测试中已 stub,Task 5/6 完成后一起验证

**Interfaces:**
- Consumes: `CapabilityContext` / `CapabilityContextFactory` / `ToolExecutionContext` / `LlmProvider`
- Produces:
  - `public class SharedMemoryContext(memoryStore, llmProvider, registry, memoryFileLimit, forgetDuration) : CapabilityContext`
  - `internal class SharedMemoryContextFactory(...) : CapabilityContextFactory<SharedMemoryContext>`
  - `SharedMemoryContext.llmProvider` 字段类型为 `LlmProvider`(非 null,因 factory 已做 null 回退)

- [ ] **Step 1: 实现 SharedMemoryContext + Factory**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryContext.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.ToolExecutionContext
import java.time.Duration

/**
 * Memory 工具执行时的上下文。
 *
 * 携带:
 * - [memoryStore]: 文件 IO 抽象,load/save 共用
 * - [llmProvider]: 压缩用 LLM(非空 —— factory 已做 null 回退到 [ToolExecutionContext.agentContext.llmProvider])
 * - [registry]: 用于 save_shared_memory 创建新话题后触发 rescan
 * - [memoryFileLimit]: 单文件字符上限,超则同步压缩(并入 save 流程)
 * - [forgetDuration]: 遗忘时间,文件超过此时长未被读写则删
 */
public class SharedMemoryContext(
    public val memoryStore: SharedMemoryStore,
    public val llmProvider: LlmProvider,
    public val registry: SharedMemoryRegistry,
    public val memoryFileLimit: Int,
    public val forgetDuration: Duration,
) : CapabilityContext

/**
 * 桥接 [ToolExecutionContext] 到 [SharedMemoryContext]。
 *
 * llmProvider 为 null 时回退到 [ToolExecutionContext.agentContext.llmProvider]。
 */
internal class SharedMemoryContextFactory(
    private val memoryStore: SharedMemoryStore,
    private val llmProvider: LlmProvider?,
    private val registry: SharedMemoryRegistry,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
) : CapabilityContextFactory<SharedMemoryContext> {
    override fun create(context: ToolExecutionContext): SharedMemoryContext {
        val effectiveLlmProvider = llmProvider ?: context.agentContext.llmProvider
        return SharedMemoryContext(memoryStore, effectiveLlmProvider, registry, memoryFileLimit, forgetDuration)
    }
}
```

- [ ] **Step 2: 暂不跑测试(还差 SharedMemoryRegistry,Task 7 完成)**

---

## Task 7: SharedMemoryRegistry —— 注册中心 + rescan

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistry.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistryTest.kt`

**Interfaces:**
- Consumes: `CapabilityRegistry<SharedMemory, Unit, SharedMemoryContext>` / `DefaultCapabilityRegistry` / `SharedMemoryStore` / `SharedMemoryFile`
- Produces:
  - `public class SharedMemoryRegistry(memoryStore, memoryFileLimit, forgetDuration, llmProvider) : CapabilityRegistry<SharedMemory, Unit, SharedMemoryContext>`
  - `public suspend fun rescan()` — 扫描目录,同步 registry 与文件系统状态

- [ ] **Step 1: 写失败测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistryTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedMemoryRegistryTest {

    private val tempDir = Files.createTempDirectory("registry-test")
    private val store = PathSharedMemoryStore(tempDir)
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant(""),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun seedFile(name: String, description: String) = runTest {
        store.write(name, SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter(name, description, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")),
            "body",
        ))
    }

    @Test
    fun `rescan picks up new files`() = runTest {
        seedFile("travel", "旅行")
        seedFile("food", "美食")
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("travel", "food"), names)
    }

    @Test
    fun `rescan removes entries for deleted files`() = runTest {
        seedFile("travel", "旅行")
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        store.delete("travel")
        registry.rescan()
        assertTrue(registry.all().none { it.name == "travel" })
    }

    @Test
    fun `rescan adds new files created after initial scan`() = runTest {
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        assertTrue(registry.all().isEmpty())

        seedFile("travel", "旅行")
        registry.rescan()
        assertEquals(1, registry.all().size)
        assertEquals("travel", registry.all().first().name)
    }

    @Test
    fun `rescan does not include user file`() = runTest {
        store.write("user", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("user", "用户画像", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")),
            "user body",
        ))
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        assertTrue(registry.all().none { it.name == "user" })
    }

    @Test
    fun `capabilityType is memory`() {
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        assertEquals("shared_memory", registry.capabilityType)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryRegistryTest"`
Expected: FAIL — `SharedMemoryRegistry` 未定义

- [ ] **Step 3: 实现 SharedMemoryRegistry**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistry.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.llm.LlmProvider
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 共享记忆注册中心,负责 (re)scan 目录、维护 name → SharedMemory 映射。
 *
 * - [rescan] 扫描根目录所有 `.md` 文件(由 [SharedMemoryStore.list] 返回,已排除 `user.md`),
 *   解析 frontmatter,同步 registry 与文件系统状态。
 * - 已删除的文件从 registry 移除;新增的文件加入 registry;变更的 frontmatter 反映到 description。
 * - 线程安全:rescan 与 register 互斥(用 [Mutex])。
 */
public class SharedMemoryRegistry(
    private val memoryStore: SharedMemoryStore,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
    private val llmProvider: LlmProvider?,
) : CapabilityRegistry<SharedMemory, Unit, SharedMemoryContext> by DefaultCapabilityRegistry(
    capabilityType = SharedMemory.CAPABILITY_TYPE
) {
    private val mutex = Mutex()

    /** 扫描根目录,同步 registry 与文件系统状态。 */
    public suspend fun rescan(): Unit = mutex.withLock {
        val files = memoryStore.list().toSet()
        val existing = all().associateBy { it.name }

        // 移除已不存在的
        existing.values.forEach { mem ->
            if (mem.name !in files) {
                unregister(mem.name)
            }
        }

        // 新增未注册的
        for (name in files) {
            if (existing[name] == null) {
                val memory = loadFromStore(name) ?: continue
                register(memory)
            }
        }
    }

    private suspend fun loadFromStore(name: String): SharedMemory? {
        val content = memoryStore.read(name) ?: return null
        return try {
            val (frontmatter, _) = SharedMemoryFile.parse(content)
            DefaultSharedMemory(frontmatter.name, frontmatter.description, memoryStore)
        } catch (e: Exception) {
            null // 解析失败的文件跳过
        }
    }
}
```

- [ ] **Step 4: 跑 Task 5/6/7 所有测试验证通过**

Run: `./gradlew :agent:memory:shared:test`
Expected: PASS — SharedMemoryTest、SharedMemoryRegistryTest 全部通过

- [ ] **Step 5: 提交 Task 5/6/7**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemory.kt \
        agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryContext.kt \
        agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistry.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryTest.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryRegistryTest.kt
git commit -m "feat(shared-memory): SharedMemory/SharedMemoryContext/SharedMemoryRegistry 核心类型

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: SaveSharedMemoryTool —— 写入工具(不含压缩/遗忘)

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt`

**Interfaces:**
- Consumes: `Tool` / `ToolExecutionResult` / `ToolParameters` / `SharedMemoryStore` / `SharedMemoryFile` / `SharedMemoryRegistry` / `CapabilityContextFactory<SharedMemoryContext>`
- Produces:
  - `internal class SaveSharedMemoryTool(registry, contextFactory) : Tool`
  - 工具名 `save_shared_memory`,参数 `{name, content, mode}`
  - 副作用:新话题创建触发 `rescan`,已有话题更新 `lastAccessedAt`,同步压缩入口(本 task 占位,Task 10 实现)

**注意:** 本 task 不实现压缩与遗忘检查,只跑通读写路径。Task 10/11 会扩展。

- [ ] **Step 1: 写失败测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.ToolExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveSharedMemoryToolTest {

    private val tempDir = Files.createTempDirectory("save-tool-test")
    private val store = PathSharedMemoryStore(tempDir)
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant("summary"),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    private val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
    private val ctxFactory = object : CapabilityContextFactory<SharedMemoryContext> {
        override fun create(context: ToolExecutionContext) = SharedMemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = registry,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
        )
    }
    private val tool = SaveSharedMemoryTool(registry, ctxFactory)

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun execContext(): ToolExecutionContext = ToolExecutionContext(
        toolCallId = "test-call",
        agentContext = AgentContext(
            id = "test",
            persona = io.github.yeyi.agent.AgentPersona("test"),
            llmProvider = stubLlm,
            memory = io.github.yeyi.agent.memory.InMemoryMemory(),
            maxRounds = 20,
            maxIterations = 20,
            tools = emptyList(),
            modalityAdapter = io.github.yeyi.agent.modality.DefaultModalityAdapter(),
            hook = io.github.yeyi.agent.NoOpAgentHook,
        ),
    )

    private fun args(
        name: String,
        content: String = "",
        mode: String = "append",
        oldText: String? = null,
        newText: String? = null,
    ): JsonObject = buildMap {
        put("name", JsonPrimitive(name))
        put("content", JsonPrimitive(content))
        put("mode", JsonPrimitive(mode))
        oldText?.let { put("old_text", JsonPrimitive(it)) }
        newText?.let { put("new_text", JsonPrimitive(it)) }
    }.let { JsonObject(it) }

    @Test
    fun `append to new topic creates file and triggers rescan`() = runTest {
        registry.rescan()
        val result = tool.execute(args("travel", "去东南亚"), execContext())
        assertFalse(result.isError)
        assertTrue(store.exists("travel"))
        assertTrue(registry.all().any { it.name == "travel" })
    }

    @Test
    fun `append to existing topic concatenates body`() = runTest {
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "原内容",
        ))
        registry.rescan()
        tool.execute(args("travel", "新内容", "append"), execContext())
        val parsed = SharedMemoryFile.parse(store.read("travel")!!)
        assertEquals("原内容\n\n新内容", parsed.body)
    }

    @Test
    fun `update replaces old_text with new_text`() = runTest {
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "喜欢辣的食物\n\n常去东南亚",
        ))
        registry.rescan()
        tool.execute(args("travel", mode = "update", oldText = "喜欢辣的食物", newText = "口味偏清淡"), execContext())
        val parsed = SharedMemoryFile.parse(store.read("travel")!!)
        assertEquals("口味偏清淡\n\n常去东南亚", parsed.body)
    }

    @Test
    fun `update with empty new_text deletes the fragment`() = runTest {
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "喜欢辣\n\n常去东南亚",
        ))
        registry.rescan()
        tool.execute(args("travel", mode = "update", oldText = "喜欢辣\n\n", newText = ""), execContext())
        val parsed = SharedMemoryFile.parse(store.read("travel")!!)
        assertEquals("常去东南亚", parsed.body)
    }

    @Test
    fun `update with missing old_text returns error`() = runTest {
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "原内容",
        ))
        registry.rescan()
        val result = tool.execute(args("travel", mode = "update", oldText = "不存在", newText = "新"), execContext())
        assertTrue(result.isError)
    }

    @Test
    fun `update on non-existing memory returns error`() = runTest {
        registry.rescan()
        val result = tool.execute(args("ghost", mode = "update", oldText = "x", newText = "y"), execContext())
        assertTrue(result.isError)
    }

    @Test
    fun `save updates lastAccessedAt`() = runTest {
        val oldTime = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", oldTime, oldTime),
            "原内容",
        ))
        registry.rescan()
        val before = java.time.Instant.now()
        tool.execute(args("travel", "追加", "append"), execContext())
        val after = java.time.Instant.now()
        val parsed = SharedMemoryFile.parse(store.read("travel")!!)
        assertTrue(!parsed.frontmatter.lastAccessedAt.isBefore(before))
        assertTrue(!parsed.frontmatter.lastAccessedAt.isAfter(after))
    }

    @Test
    fun `missing name returns error`() = runTest {
        val result = tool.execute(JsonObject(mapOf("content" to JsonPrimitive("x"))), execContext())
        assertTrue(result.isError)
    }

    @Test
    fun `missing content returns error`() = runTest {
        val result = tool.execute(JsonObject(mapOf("name" to JsonPrimitive("x"))), execContext())
        assertTrue(result.isError)
    }

    @Test
    fun `invalid mode returns error`() = runTest {
        val result = tool.execute(args("x", "y", "bad-mode"), execContext())
        assertTrue(result.isError)
    }

    @Test
    fun `save to user creates user file`() = runTest {
        tool.execute(args("user", "用户画像:开发者"), execContext())
        assertTrue(store.exists("user"))
        val parsed = SharedMemoryFile.parse(store.read("user")!!)
        assertEquals("用户画像:开发者", parsed.body)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SaveSharedMemoryToolTest"`
Expected: FAIL — `SaveSharedMemoryTool` 未定义

- [ ] **Step 3: 实现 SaveSharedMemoryTool(不含压缩/遗忘)**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM 写入共享记忆的工具。
 *
 * 参数:
 * - name: 话题名(对应文件名,不含 .md)
 * - content: 写入内容(markdown)。append 必填;update 忽略
 * - mode: "append" 追加到正文末尾 / "update" 局部替换(配 old_text/new_text)
 * - old_text: update 模式必填,要被替换的原文片段
 * - new_text: update 模式必填,替换后的新片段(空串表示删除)
 *
 * 副作用:
 * - 若 name 是新话题,创建文件并触发 registry.rescan()(update 不允许新建)
 * - 更新 frontmatter.lastAccessedAt
 * - (Task 10/11 加)同步压缩 + 遗忘检查
 *
 * 返回:写入确认信息
 *
 * 并发语义:本工具自身不持锁,串行性由 SharedMemoryStore 的 per-name 互斥保证。
 */
internal class SaveSharedMemoryTool(
    private val registry: SharedMemoryRegistry,
    private val contextFactory: CapabilityContextFactory<SharedMemoryContext>,
) : Tool {
    override val name: String = "save_shared_memory"

    override val description: String = """
        将信息写入共享记忆。当用户告诉你值得跨会话记住的事实(偏好、背景、约定等)时调用本工具。

        参数:
        - name: 话题名(英文短词,如 "travel-preferences")。若话题已存在则更新;若不存在则新建。
        - content: markdown 内容。append 模式下追加到现有内容末尾;update 模式下忽略,改用 old_text/new_text。
        - mode: "append" 或 "update",默认 "append"。
          - append: 追加 content 到现有正文末尾。新话题创建也走此模式(旧正文为空)。
          - update: 局部替换。需配 old_text/new_text,工具读旧正文找到 old_text 替换为 new_text。
                    new_text 为空串表示删除该片段。找不到 old_text 则返回错误,不修改文件。
        - old_text: 仅 update 模式必填。要被替换的原文片段,必须与现有正文中的文本精确匹配。
        - new_text: 仅 update 模式必填。替换后的新片段。

        特殊话题:
        - "user": 写入用户画像,每轮会主动注入 system prompt,不会遗忘。用户说"忘记 xxx"或
          "改一下我之前说的 xxx"时,用 update 模式精确替换对应片段。
    """.trimIndent()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","update"]},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["name","content"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val obj = arguments.jsonObject
        val name = (obj["name"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        val mode = (obj["mode"] as? JsonPrimitive)?.content ?: "append"
        val content = (obj["content"] as? JsonPrimitive)?.content
            ?: if (mode == "append") return ToolExecutionResult.error("Missing 'content'") else ""
        val oldText = (obj["old_text"] as? JsonPrimitive)?.content
            ?: if (mode == "update") return ToolExecutionResult.error("Missing 'old_text'") else null
        val newText = (obj["new_text"] as? JsonPrimitive)?.content
            ?: if (mode == "update") return ToolExecutionResult.error("Missing 'new_text'") else null

        val ctx = contextFactory.create(context)
        val store = ctx.memoryStore
        val now = Instant.now()

        val isNew = !store.exists(name)
        val (frontmatter, body) = if (isNew) {
            if (mode == "update") return ToolExecutionResult.error("Cannot update a non-existing memory '$name'")
            SharedMemoryFile.Frontmatter(name, descriptionFromContent(content), now, now) to ""
        } else {
            val raw = store.read(name)!!
            val (fm, b) = SharedMemoryFile.parse(raw)
            fm.copy(lastAccessedAt = now) to b
        }

        // 遗忘检查(Task 11 实现,本 task 占位 no-op)

        val newBody = when (mode) {
            "append" -> if (body.isBlank()) content else "$body\n\n$content"
            "update" -> {
                if (!body.contains(oldText!!)) {
                    return ToolExecutionResult.error("old_text not found in memory '$name'")
                }
                body.replace(oldText, newText!!)
            }
            else -> return ToolExecutionResult.error("Invalid mode: $mode")
        }

        // 同步压缩(Task 10 实现,本 task 占位 no-op:超限也不压缩)
        val finalBody = newBody

        store.write(name, SharedMemoryFile.serialize(frontmatter, finalBody))

        if (isNew) registry.rescan()

        return ToolExecutionResult.success("Saved memory '$name' (${finalBody.length} chars).")
    }

    // Task 10 实现同步压缩:超 memoryFileLimit 时调 LLM 总结,返回 summary;失败返回 null 保留 newBody
    private suspend fun compressMemory(
        ctx: SharedMemoryContext,
        name: String,
        frontmatter: SharedMemoryFile.Frontmatter,
        body: String,
    ): String? = null

    private fun descriptionFromContent(content: String): String =
        content.lineSequence().firstOrNull()?.removePrefix("#")?.trim()?.take(80)?.ifBlank { "Shared memory" }
            ?: "Shared memory"
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SaveSharedMemoryToolTest"`
Expected: PASS — 测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt
git commit -m "feat(shared-memory): SaveSharedMemoryTool 写入工具(append/update)

不含压缩与遗忘,Task 10/11 扩展。mode 收敛为 append/update,
update 配 old_text/new_text 支持局部替换与删除。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: SharedMemoryPlugin + DSL —— 接线与 user.md 初始化

**Files:**
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPlugin.kt`
- Create: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryExtensions.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPluginTest.kt`

**Interfaces:**
- Consumes: `AgentPlugin` / `AgentPluginContext` / `CapabilityAdapter` / `SharedMemoryStore` / `SharedMemoryRegistry` / `SaveSharedMemoryTool` / `SharedMemoryFile`
- Produces:
  - `public object SharedMemoryPluginConfig` — placeholder config 类型(满足 `AgentPlugin<C : Any>` 约束)
  - `internal class SharedMemoryPlugin(...) : AgentPlugin<SharedMemoryPluginConfig>`
  - `public fun AgentBuilder.sharedMemory(directory, memoryFileLimit, forgetDuration, llmProvider, memoryStore, enableDelegateAdaptMode)`

- [ ] **Step 1: 写失败测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPluginTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMemoryPluginTest {

    private val tempDir = Files.createTempDirectory("plugin-test")
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant(""),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private class FakePluginContext : AgentPluginContext {
        val tools = mutableListOf<Tool>()
        val instructions = mutableListOf<String>()
        override fun registerTool(tool: Tool) { tools.add(tool) }
        override fun appendInstructions(content: String) { instructions.add(content) }
    }

    @Test
    fun `install creates user md if missing`() = runTest {
        val store = PathSharedMemoryStore(tempDir)
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = SharedMemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        plugin.install(FakePluginContext())
        assertTrue(store.exists("user"))
        val parsed = SharedMemoryFile.parse(store.read("user")!!)
        assertEquals("user", parsed.frontmatter.name)
        assertEquals("", parsed.body)
    }

    @Test
    fun `install does not overwrite existing user md`() = runTest {
        val store = PathSharedMemoryStore(tempDir)
        val existing = SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("user", "已存在", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "已有内容",
        )
        store.write("user", existing)

        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = SharedMemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        plugin.install(FakePluginContext())
        val parsed = SharedMemoryFile.parse(store.read("user")!!)
        assertEquals("已有内容", parsed.body)
    }

    @Test
    fun `install injects user body to instructions when non-blank`() = runTest {
        val store = PathSharedMemoryStore(tempDir)
        store.write("user", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("user", "用户画像", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "用户是开发者",
        ))
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = SharedMemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        val ctx = FakePluginContext()
        plugin.install(ctx)
        assertTrue(ctx.instructions.any { it.contains("用户是开发者") })
    }

    @Test
    fun `install registers load_shared_memory and save_shared_memory tools`() = runTest {
        val store = PathSharedMemoryStore(tempDir)
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = SharedMemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        val ctx = FakePluginContext()
        plugin.install(ctx)
        val names = ctx.tools.map { it.name }.toSet()
        assertTrue("load_shared_memory" in names)
        assertTrue("save_shared_memory" in names)
    }

    @Test
    fun `install picks up existing topic files via rescan`() = runTest {
        val store = PathSharedMemoryStore(tempDir)
        store.write("travel", SharedMemoryFile.serialize(
            SharedMemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "body",
        ))
        val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = SharedMemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        plugin.install(FakePluginContext())
        assertTrue(registry.all().any { it.name == "travel" })
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryPluginTest"`
Expected: FAIL — `SharedMemoryPlugin` / `SharedMemoryExtensions` 未定义

- [ ] **Step 3: 实现 SharedMemoryPlugin + SharedMemoryPluginConfig**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPlugin.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.capability.CapabilityAdapter
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * 占位 config 类型,满足 [AgentPlugin] 的 `C : Any` 约束。
 *
 * SharedMemoryPlugin 的所有配置在构造器完成,configure block 为 no-op。
 */
public object SharedMemoryPluginConfig

/**
 * 共享记忆插件的接线类。
 *
 * 直接实现 [AgentPlugin] 而非继承 [io.github.yeyi.agent.capability.CapabilityPlugin],
 * 因为需要在 install 阶段调 [AgentPluginContext.appendInstructions] 注入 user.md ——
 * `CapabilityPlugin.install` 是 `final override` 且不调 `appendInstructions`。
 *
 * 仍复用 [CapabilityAdapter.of] 拼装 load_shared_memory(委托模式),与 Skill 形态对称。
 *
 * install 流程:
 * 1. ensureUserMemory: 若 `user.md` 不存在,创建空模板
 * 2. registry.rescan(): 扫描所有话题文件,构建内存注册表
 * 3. 读 user.md 正文,通过 appendInstructions 注入 persona 能力区
 * 4. CapabilityAdapter.of(registry, ctxFactory, null, enableDelegateAdaptMode).adapt()
 *    → load_shared_memory 工具,registerTool
 * 5. registerTool(save_shared_memory)
 */
internal class SharedMemoryPlugin(
    private val directory: Path,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
    private val llmProvider: LlmProvider?,
    private val memoryStore: SharedMemoryStore,
    private val enableDelegateAdaptMode: Boolean,
    private val registry: SharedMemoryRegistry,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    private val ctxFactory: CapabilityContextFactory<SharedMemoryContext> = SharedMemoryContextFactory(
        memoryStore = memoryStore,
        llmProvider = llmProvider,
        registry = registry,
        memoryFileLimit = memoryFileLimit,
        forgetDuration = forgetDuration,
    )

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) {
        // no-op:配置已在构造器完成
    }

    override fun install(context: AgentPluginContext) {
        runBlocking {
            ensureUserMemory()
            registry.rescan()
        }

        // 注入 user.md 正文到 persona 能力区
        val userRaw = runBlocking { memoryStore.read("user") }
        if (userRaw != null) {
            val (_, body) = SharedMemoryFile.parse(userRaw)
            if (body.isNotBlank()) {
                context.appendInstructions(body)
            }
        }

        // 注册 load_shared_memory(委托模式,继承 CapabilityLoadTool)
        CapabilityAdapter.of(registry, ctxFactory, null, enableDelegateAdaptMode)
            .adapt()
            .forEach { context.registerTool(it) }

        // 注册 save_shared_memory
        context.registerTool(SaveSharedMemoryTool(registry, ctxFactory))
    }

    private suspend fun ensureUserMemory() {
        if (!memoryStore.exists("user")) {
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
            memoryStore.write("user", empty)
        }
    }
}
```

- [ ] **Step 4: 实现 SharedMemoryExtensions**

新建 `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryExtensions.kt`:

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
 * @param memoryStore 文件 IO 抽象;null 时用 [PathSharedMemoryStore]。默认 null
 * @param enableDelegateAdaptMode load_shared_memory 是否走委托模式(单一工具 + name 路由)。默认 true
 */
public fun AgentBuilder.sharedMemory(
    directory: Path,
    memoryFileLimit: Int = 8000,
    forgetDuration: Duration = Duration.ofDays(30),
    llmProvider: LlmProvider? = null,
    memoryStore: SharedMemoryStore? = null,
    enableDelegateAdaptMode: Boolean = true,
) {
    val store = memoryStore ?: PathSharedMemoryStore(directory)
    val registry = SharedMemoryRegistry(store, memoryFileLimit, forgetDuration, llmProvider)
    val plugin = SharedMemoryPlugin(
        directory = directory,
        memoryFileLimit = memoryFileLimit,
        forgetDuration = forgetDuration,
        llmProvider = llmProvider,
        memoryStore = store,
        enableDelegateAdaptMode = enableDelegateAdaptMode,
        registry = registry,
    )
    plugin(plugin)
}
```

- [ ] **Step 5: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryPluginTest"`
Expected: PASS — 5 个测试全部通过

- [ ] **Step 6: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPlugin.kt \
        agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryExtensions.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryPluginTest.kt
git commit -m "feat(shared-memory): SharedMemoryPlugin 接线 + AgentBuilder DSL

- install 时创建 user.md 空模板,rescan 话题,appendInstructions 注入 user 正文
- 通过 CapabilityAdapter.of 拼装 load_shared_memory,auxiliary 注册 save_shared_memory
- SharedMemoryPluginConfig object 满足 AgentPlugin<C : Any> 约束

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: 同步压缩(并入 save 流程)

**Files:**
- Modify: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt`
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt`(追加压缩测试)

**Interfaces:**
- Consumes: `SharedMemoryContext.llmProvider` / `ChatRequest` / `ChatMessage.System` / `ChatResponse.message.content`
- Produces: `SaveSharedMemoryTool.compressMemory` 实现完成,save 后超 `memoryFileLimit` 同步压缩并覆盖写回(save 返回时文件已是压缩态)

**注意:** 压缩是 save 流程内的一步,与 write 共享 store 的 per-name 锁。不是独立协程。
压缩失败(`llmProvider` 为 null 或 LLM 抛错)返回 null,save 保留 newBody 不特殊处理。

- [ ] **Step 1: 写失败测试 — 压缩被触发且文件被覆盖**

在 `SaveSharedMemoryToolTest.kt` 末尾追加:

```kotlin
@Test
fun `save triggers sync compression when body exceeds memoryFileLimit`() = runTest {
    val compressingLlm = object : LlmProvider {
        override val name: String = "compressor"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant("压缩后内容"),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    val smallLimit = 10
    val compressingRegistry = SharedMemoryRegistry(store, smallLimit, Duration.ofDays(30), compressingLlm)
    val compressingCtxFactory = object : CapabilityContextFactory<SharedMemoryContext> {
        override fun create(context: ToolExecutionContext) = SharedMemoryContext(
            memoryStore = store,
            llmProvider = compressingLlm,
            registry = compressingRegistry,
            memoryFileLimit = smallLimit,
            forgetDuration = Duration.ofDays(30),
        )
    }
    val compressingTool = SaveSharedMemoryTool(compressingRegistry, compressingCtxFactory)

    // 写入超限内容,save 返回时文件应已是压缩态
    val longContent = "这是一个很长的内容,超过 10 字符上限"
    compressingTool.execute(args("long-topic", longContent), execContext())

    val parsed = SharedMemoryFile.parse(store.read("long-topic")!!)
    assertEquals("压缩后内容", parsed.body)
}

@Test
fun `save keeps newBody when compression fails`() = runTest {
    val failingLlm = object : LlmProvider {
        override val name: String = "failing"
        override suspend fun chat(request: ChatRequest) = throw RuntimeException("LLM down")
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    val smallLimit = 10
    val failingRegistry = SharedMemoryRegistry(store, smallLimit, Duration.ofDays(30), failingLlm)
    val failingCtxFactory = object : CapabilityContextFactory<SharedMemoryContext> {
        override fun create(context: ToolExecutionContext) = SharedMemoryContext(
            memoryStore = store,
            llmProvider = failingLlm,
            registry = failingRegistry,
            memoryFileLimit = smallLimit,
            forgetDuration = Duration.ofDays(30),
        )
    }
    val failingTool = SaveSharedMemoryTool(failingRegistry, failingCtxFactory)

    val longContent = "这是一个很长的内容,超过 10 字符上限"
    failingTool.execute(args("long-topic", longContent), execContext())

    // 压缩失败,文件保留 newBody
    val parsed = SharedMemoryFile.parse(store.read("long-topic")!!)
    assertEquals(longContent, parsed.body)
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SaveSharedMemoryToolTest.save triggers sync compression when body exceeds memoryFileLimit"`
Expected: FAIL — body 仍是原始长内容(`compressMemory` 返回 null)

- [ ] **Step 3: 实现 compressMemory + 接入 save 流程**

修改 `SaveSharedMemoryTool.kt`:

1. `compressMemory` 改为返回 `String?`(成功返回 summary,失败返回 null):

```kotlin
private suspend fun compressMemory(
    ctx: SharedMemoryContext,
    body: String,
): String? {
    val llm = ctx.llmProvider ?: return null
    val targetSize = ctx.memoryFileLimit / 2
    val prompt = """
        请总结以下共享记忆内容,保留关键信息,压缩到 $targetSize 字符以内。
        只输出压缩后的正文,不要任何前缀说明。

        原始内容:
        $body
    """.trimIndent()

    val request = io.github.yeyi.agent.llm.ChatRequest(
        messages = listOf(io.github.yeyi.agent.llm.ChatMessage.System(prompt)),
    )
    return runCatching {
        llm.chat(request).message.content
    }.getOrNull()
}
```

2. `execute` 中的 `finalBody` 计算改为:

```kotlin
val finalBody = if (newBody.length > ctx.memoryFileLimit) {
    compressMemory(ctx, newBody) ?: newBody
} else {
    newBody
}
```

压缩在 write 之前完成,store.write 一次性写入最终态。per-name 锁由 store 保证,save 与压缩同锁串行。

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SaveSharedMemoryToolTest"`
Expected: PASS — 所有测试通过(含新增两个压缩测试)

- [ ] **Step 5: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt
git commit -m "feat(shared-memory): 同步压缩,超 memoryFileLimit 时在 save 流程内调 LLM 总结

压缩并入 save 流程,与 write 共享 store per-name 锁,不再异步。
压缩失败保留 newBody,下次 save 再次触发。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: 遗忘检查(集成到 load/save 路径)

**Files:**
- Modify: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemory.kt`(load 时检查)
- Modify: `agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt`(save 时检查)
- Test: 扩展 `SharedMemoryTest.kt` 和 `SaveSharedMemoryToolTest.kt`

**Interfaces:**
- Consumes: `SharedMemoryContext.forgetDuration` / `SharedMemoryContext.memoryStore` / `SharedMemoryContext.registry`
- Produces: load/save 路径在操作前检查 `lastAccessedAt`,超阈值则删除文件 + rescan

- [ ] **Step 1: 写失败测试 — load 时遗忘过期文件**

在 `SharedMemoryTest.kt` 末尾追加:

```kotlin
@Test
fun `load deletes file when lastAccessedAt exceeds forgetDuration`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60) // 60 秒前
    seedMemory("old-topic", "旧内容", lastAccessedAt = oldTime)
    val shortForgetDuration = java.time.Duration.ofSeconds(30) // 30 秒就遗忘

    val memory = DefaultSharedMemory("old-topic", "desc for old-topic", store)
    // 用短 forgetDuration 的 context
    val stubLlm = object : io.github.yeyi.agent.llm.LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
            io.github.yeyi.agent.llm.ChatResponse(
                io.github.yeyi.agent.llm.ChatMessage.Assistant(""),
                null,
                io.github.yeyi.agent.llm.FinishReason.Stop,
            )
        override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): kotlinx.coroutines.flow.Flow<io.github.yeyi.agent.llm.ChatResponseEvent> =
            kotlinx.coroutines.flow.flow { }
    }
    val registry = SharedMemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val ctx = SharedMemoryContext(store, stubLlm, registry, 8000, shortForgetDuration)

    val result = memory.activate(null, ctx)
    assertEquals("(memory expired)", result)
    assertFalse(store.exists("old-topic"))
}

@Test
fun `load does not forget user file even when expired`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60)
    seedMemory("user", "用户画像", lastAccessedAt = oldTime)
    val shortForgetDuration = java.time.Duration.ofSeconds(30)

    val memory = DefaultSharedMemory("user", "用户画像", store)
    val stubLlm = object : io.github.yeyi.agent.llm.LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
            io.github.yeyi.agent.llm.ChatResponse(
                io.github.yeyi.agent.llm.ChatMessage.Assistant(""),
                null,
                io.github.yeyi.agent.llm.FinishReason.Stop,
            )
        override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): kotlinx.coroutines.flow.Flow<io.github.yeyi.agent.llm.ChatResponseEvent> =
            kotlinx.coroutines.flow.flow { }
    }
    val registry = SharedMemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val ctx = SharedMemoryContext(store, stubLlm, registry, 8000, shortForgetDuration)

    memory.activate(null, ctx)
    assertTrue(store.exists("user")) // user 不被遗忘
}
```

- [ ] **Step 2: 写失败测试 — save 时遗忘过期文件**

在 `SaveSharedMemoryToolTest.kt` 末尾追加:

```kotlin
@Test
fun `save deletes file when lastAccessedAt exceeds forgetDuration`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60)
    store.write("old-topic", SharedMemoryFile.serialize(
        SharedMemoryFile.Frontmatter("old-topic", "旧", oldTime, oldTime),
        "原内容",
    ))
    val shortForgetDuration = Duration.ofSeconds(30)
    val shortForgetRegistry = SharedMemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val shortForgetCtxFactory = object : CapabilityContextFactory<SharedMemoryContext> {
        override fun create(context: ToolExecutionContext) = SharedMemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = shortForgetRegistry,
            memoryFileLimit = 8000,
            forgetDuration = shortForgetDuration,
        )
    }
    val shortForgetTool = SaveSharedMemoryTool(shortForgetRegistry, shortForgetCtxFactory)
    shortForgetRegistry.rescan()

    val result = shortForgetTool.execute(args("old-topic", "新内容", "append"), execContext())
    assertTrue(result.isError.not())
    assertTrue(result.parts.first().let { it is io.github.yeyi.agent.llm.ContentPart.Text && it.text.contains("expired") })
    assertFalse(store.exists("old-topic"))
}
```

- [ ] **Step 3: 跑测试验证失败**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryTest.load deletes file when lastAccessedAt exceeds forgetDuration" --tests "io.github.yeyi.agent.memory.shared.SaveSharedMemoryToolTest.save deletes file when lastAccessedAt exceeds forgetDuration"`
Expected: FAIL — 遗忘逻辑未实现

- [ ] **Step 4: 改 DefaultSharedMemory.load 集成遗忘检查**

修改 `SharedMemory.kt` 的 `DefaultSharedMemory`:

```kotlin
internal class DefaultSharedMemory(
    override val name: String,
    override val description: String,
    private val memoryStore: SharedMemoryStore,
) : SharedMemory {
    override suspend fun load(): String = "(load should not be called without context)"

    override suspend fun activate(arguments: Unit?, context: SharedMemoryContext): String {
        val raw = memoryStore.read(name) ?: return "(memory expired)"
        val (frontmatter, body) = SharedMemoryFile.parse(raw)
        val now = Instant.now()

        // 遗忘检查(user 豁免)
        if (name != "user") {
            val elapsed = java.time.Duration.between(frontmatter.lastAccessedAt, now)
            if (elapsed > context.forgetDuration) {
                memoryStore.delete(name)
                context.registry.rescan()
                return "(memory expired)"
            }
        }

        // 更新 lastAccessedAt 并写回
        val updated = frontmatter.copy(lastAccessedAt = now)
        memoryStore.write(name, SharedMemoryFile.serialize(updated, body))
        return raw
    }
}
```

**注:** 原来的 `load()` 抽象方法保留(满足接口),但实际逻辑移到 `activate` —— 因为遗忘检查需要 `SharedMemoryContext.forgetDuration`,而 `load()` 无参。`activate(null, context)` 是 `CapabilityLoadTool.execute` 的实际入口,会传 context。`load()` 改为返回错误提示,防止误调。

- [ ] **Step 5: 改 SaveSharedMemoryTool.execute 集成遗忘检查**

修改 `SaveSharedMemoryTool.kt` 的 `execute`,在 `frontmatter, body` 解析后、`newBody` 计算前插入遗忘检查:

```kotlin
// 遗忘检查:user 豁免;若已超阈值,删除并返回
if (name != "user") {
    val elapsed = java.time.Duration.between(frontmatter.lastAccessedAt, now)
    if (elapsed > ctx.forgetDuration) {
        store.delete(name)
        registry.rescan()
        return ToolExecutionResult.success("Memory '$name' was expired and deleted.")
    }
}
```

完整 `execute` 方法:

```kotlin
override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
    val obj = arguments.jsonObject
    val name = (obj["name"] as? JsonPrimitive)?.content
        ?: return ToolExecutionResult.error("Missing 'name'")
    val mode = (obj["mode"] as? JsonPrimitive)?.content ?: "append"
    val content = (obj["content"] as? JsonPrimitive)?.content
        ?: if (mode == "append") return ToolExecutionResult.error("Missing 'content'") else ""
    val oldText = (obj["old_text"] as? JsonPrimitive)?.content
        ?: if (mode == "update") return ToolExecutionResult.error("Missing 'old_text'") else null
    val newText = (obj["new_text"] as? JsonPrimitive)?.content
        ?: if (mode == "update") return ToolExecutionResult.error("Missing 'new_text'") else null

    val ctx = contextFactory.create(context)
    val store = ctx.memoryStore
    val now = Instant.now()

    val isNew = !store.exists(name)
    val (frontmatter, body) = if (isNew) {
        if (mode == "update") return ToolExecutionResult.error("Cannot update a non-existing memory '$name'")
        SharedMemoryFile.Frontmatter(name, descriptionFromContent(content), now, now) to ""
    } else {
        val raw = store.read(name)!!
        val (fm, b) = SharedMemoryFile.parse(raw)
        fm to b  // 保留原 lastAccessedAt 用于遗忘检查
    }

    // 遗忘检查:user 豁免;若已超阈值,删除并返回
    if (name != "user") {
        val elapsed = java.time.Duration.between(frontmatter.lastAccessedAt, now)
        if (elapsed > ctx.forgetDuration) {
            store.delete(name)
            registry.rescan()
            return ToolExecutionResult.success("Memory '$name' was expired and deleted.")
        }
    }

    val updatedFrontmatter = frontmatter.copy(lastAccessedAt = now)
    val newBody = when (mode) {
        "append" -> if (body.isBlank()) content else "$body\n\n$content"
        "update" -> {
            if (!body.contains(oldText!!)) {
                return ToolExecutionResult.error("old_text not found in memory '$name'")
            }
            body.replace(oldText, newText!!)
        }
        else -> return ToolExecutionResult.error("Invalid mode: $mode")
    }

    val finalBody = if (newBody.length > ctx.memoryFileLimit) {
        compressMemory(ctx, newBody) ?: newBody
    } else {
        newBody
    }
    store.write(name, SharedMemoryFile.serialize(updatedFrontmatter, finalBody))

    if (isNew) registry.rescan()

    val compressed = finalBody.length < newBody.length
    return ToolExecutionResult.success(
        "Saved memory '$name' (${finalBody.length} chars).${if (compressed) " Compressed." else ""}"
    )
}
```

- [ ] **Step 6: 同步修正 Task 5 测试中的 activate 调用**

Task 5 测试里的 `memory.activate(null, stubSharedMemoryContext())` 仍可用,但 `stubSharedMemoryContext()` 的 `forgetDuration` 是 30 天,seedMemory 用的 `lastAccessedAt = Instant.parse("2026-11-25T10:00:00Z")` 在 30 天内,不会触发遗忘。如果测试跑得离 2026-11-25 太远(超过 30 天),会触发遗忘——为避免时间漂移,把 Task 5 测试里 seedMemory 用的 `lastAccessedAt` 改成 `Instant.now()`。

修改 `SharedMemoryTest.kt` 的 `seedMemory` 默认参数:

```kotlin
private fun seedMemory(name: String, body: String, lastAccessedAt: Instant = Instant.now()) {
    val content = SharedMemoryFile.serialize(
        SharedMemoryFile.Frontmatter(name, "desc for $name", Instant.parse("2026-01-01T00:00:00Z"), lastAccessedAt),
        body,
    )
    store.writeSync(name, content)
}
```

- [ ] **Step 7: 跑所有共享记忆测试验证通过**

Run: `./gradlew :agent:memory:shared:test`
Expected: PASS — 所有测试通过

- [ ] **Step 8: 提交**

```bash
git add agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SharedMemory.kt \
        agent/memory/shared/src/main/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryTool.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryTest.kt \
        agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SaveSharedMemoryToolTest.kt
git commit -m "feat(shared-memory): 遗忘检查集成到 load/save 路径

- load/save 时基于原始 lastAccessedAt 检查 forgetDuration
- user.md 豁免遗忘
- 触发遗忘时删除文件 + rescan

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: 集成测试 — 端到端 save → load 流程

**Files:**
- Test: `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryIntegrationTest.kt`

**Interfaces:**
- Consumes: 所有前序 task 的产物
- Produces: 端到端验证 save_shared_memory → load_shared_memory 完整流程

- [ ] **Step 1: 写集成测试**

新建 `agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryIntegrationTest.kt`:

```kotlin
package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.NoOpAgentHook
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import io.github.yeyi.agent.tool.ToolExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SharedMemoryIntegrationTest {

    private val tempDir = Files.createTempDirectory("ltm-integration")
    private val store = PathSharedMemoryStore(tempDir)
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant("压缩后"),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    private val registry = SharedMemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
    private val ctxFactory = object : CapabilityContextFactory<SharedMemoryContext> {
        override fun create(context: ToolExecutionContext) = SharedMemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = registry,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
        )
    }
    private val saveTool = SaveSharedMemoryTool(registry, ctxFactory)
    private val loadTool = io.github.yeyi.agent.capability.CapabilityAdapter.of(
        registry, ctxFactory, null, true,
    ).adapt().first()

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun execContext(): ToolExecutionContext = ToolExecutionContext(
        toolCallId = "test",
        agentContext = AgentContext(
            id = "test",
            persona = AgentPersona("test"),
            llmProvider = stubLlm,
            memory = InMemoryMemory(),
            maxRounds = 20,
            maxIterations = 20,
            tools = emptyList(),
            modalityAdapter = DefaultModalityAdapter(),
            hook = NoOpAgentHook,
        ),
    )

    @Test
    fun `save then load returns saved content`() = runTest {
        registry.rescan()
        saveTool.execute(
            JsonObject(mapOf(
                "name" to JsonPrimitive("travel"),
                "content" to JsonPrimitive("用户偏好海岛游"),
            )),
            execContext(),
        )

        // 等待 rescan 完成
        val loadResult = loadTool.execute(
            JsonObject(mapOf("name" to JsonPrimitive("travel"))),
            execContext(),
        )
        assertTrue(loadResult.parts.first().let { it is io.github.yeyi.agent.llm.ContentPart.Text && it.text.contains("海岛游") })
    }

    @Test
    fun `save new topic makes it visible in load tool description`() = runTest {
        registry.rescan()
        saveTool.execute(
            JsonObject(mapOf(
                "name" to JsonPrimitive("food"),
                "content" to JsonPrimitive("喜欢吃辣"),
            )),
            execContext(),
        )
        assertTrue(loadTool.description.contains("food"))
    }

    @Test
    fun `multiple saves append to same topic`() = runTest {
        registry.rescan()
        saveTool.execute(JsonObject(mapOf("name" to JsonPrimitive("hobby"), "content" to JsonPrimitive("摄影"))), execContext())
        saveTool.execute(JsonObject(mapOf("name" to JsonPrimitive("hobby"), "content" to JsonPrimitive("徒步"), "mode" to JsonPrimitive("append"))), execContext())

        val loadResult = loadTool.execute(
            JsonObject(mapOf("name" to JsonPrimitive("hobby"))),
            execContext(),
        )
        val text = (loadResult.parts.first() as io.github.yeyi.agent.llm.ContentPart.Text).text
        assertTrue(text.contains("摄影"))
        assertTrue(text.contains("徒步"))
    }
}
```

- [ ] **Step 2: 跑测试验证通过**

Run: `./gradlew :agent:memory:shared:test --tests "io.github.yeyi.agent.memory.shared.SharedMemoryIntegrationTest"`
Expected: PASS — 3 个集成测试全部通过

- [ ] **Step 3: 提交**

```bash
git add agent/memory/shared/src/test/kotlin/io/github/yeyi/agent/memory/shared/SharedMemoryIntegrationTest.kt
git commit -m "test(shared-memory): 端到端 save→load 集成测试

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 13: 验证全模块测试通过 + 演示接入(可选)

**Files:**
- 无新建文件,跑全量测试验证

- [ ] **Step 1: 跑共享记忆模块全部测试**

Run: `./gradlew :agent:memory:shared:test`
Expected: PASS — 所有测试通过

- [ ] **Step 2: 跑 capability 模块测试确认无回归**

Run: `./gradlew :agent:capability:test`
Expected: PASS

- [ ] **Step 3: 跑全项目测试确认无回归**

Run: `./gradlew test`
Expected: PASS — 所有模块测试通过

- [ ] **Step 4: (可选)在 demos/agent 加示例**

如果用户希望 demos 里有一个共享记忆示例,可在 `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/runtime/` 下新增 `memories/` 目录,放一个简单的 demo。本步骤可选,根据用户需求决定。

- [ ] **Step 5: 提交(若有 demo 改动)**

```bash
git add demos/agent/
git commit -m "demo(agent): 共享记忆示例接入

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Self-Review

### 1. Spec 覆盖检查

| Spec 章节 | 对应 Task |
|---|---|
| §3 模块结构 | Task 2 |
| §4.1 SharedMemory | Task 5 |
| §4.2 SharedMemoryContext | Task 6 |
| §4.3 SharedMemoryRegistry + rescan | Task 7 |
| §4.4 SharedMemoryPlugin | Task 9 |
| §4.5 SaveSharedMemoryTool | Task 8(基础)+ Task 10(压缩)+ Task 11(遗忘) |
| §4.6 SharedMemoryStore + PathSharedMemoryStore | Task 4 |
| §4.7 SharedMemoryFile | Task 3 |
| §4.8 SharedMemoryExtensions DSL | Task 9 |
| §5 Capability 框架改动 | Task 1 |
| §6 数据流(install / load / save / 压缩) | Task 1/8/9/10/11 |
| §7.1 user.md 处理 | Task 9 |
| §7.2 重扫并发安全 | Task 7(Mutex) |
| §7.3 压缩失败容错 | Task 10(runCatching) |
| §7.4 遗忘检查语义 | Task 11 |
| §7.5 与短期 Memory 正交 | 全局约束已声明 |
| §8 测试策略 | 每个 Task 都含 TDD 步骤 |

无遗漏。

### 2. 占位符扫描

- 无 "TBD" / "TODO" / "实现 later"
- 所有代码块都是可直接抄的完整实现
- Task 8 的 `compressMemory` 占位为空实现,但 Task 10 完整实现 —— 这是分阶段实现,不是占位

### 3. 类型一致性

- `SharedMemoryFile.Frontmatter` 字段名在所有 Task 一致:`name` / `description` / `createdAt` / `lastAccessedAt`
- `SharedMemoryContext` 构造参数顺序在 Task 6/8/9/10/11 一致:`memoryStore, llmProvider, registry, memoryFileLimit, forgetDuration`
- `SharedMemoryRegistry` 构造参数顺序在所有 Task 一致:`memoryStore, memoryFileLimit, forgetDuration, llmProvider`
- `SaveSharedMemoryTool` 构造参数顺序一致:`registry, contextFactory`(压缩并入 save 流程,无 compressScope)
- `SharedMemoryPlugin` 构造参数顺序一致:`directory, memoryFileLimit, forgetDuration, llmProvider, memoryStore, enableDelegateAdaptMode, registry`
- `AgentBuilder.sharedMemory` 参数顺序在 Task 9 与 spec §4.8 一致

### 4. 已知偏离 spec

1. **`SharedMemoryPlugin` 不继承 `CapabilityPlugin`** — spec §2/§4.4 已说明原因(`CapabilityPlugin.install` 是 final 且不支持 `appendInstructions`)。Plan Task 9 实现为直接实现 `AgentPlugin<SharedMemoryPluginConfig>`。
2. **`SharedMemoryPluginConfig` object** — `AgentPlugin<C : Any>` 不接受 `Unit`,需要占位 config 类型。spec 未提及,plan 补充。
3. **`DefaultSharedMemory.load()` 返回提示字符串** — 遗忘检查需要 context,但 `load()` 无参,实际逻辑移到 `activate`。Task 11 Step 4 已说明。
4. **`java.time.Duration` 替代 `kotlin.time.Duration`** — JVM 项目统一用 java.time,与 core 一致。
5. **frontmatter 不引入 YAML 库** — 自写最小化解析,只支持 `key: value` 行 + `---` 分隔符。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-11-25-shared-memory.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
