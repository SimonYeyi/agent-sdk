# 长期记忆插件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `:agent:long-term-memory` 模块,提供 LLM 可自主读写的 markdown 长期记忆能力,与 core 的短期会话 Memory 正交,含遗忘与压缩机制。

**Architecture:** 复用 `Capability` 框架,`load_memory` 走委托模式继承 `CapabilityLoadTool`,`save_memory` 作为 auxiliary tool;`MemoryPlugin` 直接实现 `AgentPlugin<MemoryPluginConfig>`(因 `CapabilityPlugin.install` 是 final 且不支持 `appendInstructions`),内部手工调 `CapabilityAdapter.of` 拼装 load 工具。`CapabilityLoadTool` 的 `description` / `parametersSchema` 从 `by lazy` 改为 getter,使重扫后下一轮 LLM 自动看到新话题。

**Tech Stack:** Kotlin 2.4 (JVM 21) · kotlinx.serialization.json · kotlinx.coroutines · java.nio.file.Path · java.time.Instant/Duration · JUnit 4 + kotlin-test

## Global Constraints

- 包名:`io.github.yeyi.agent.longtermemory`(避开 core 的 `io.github.yeyi.agent.memory`)
- 模块路径:`agent/long-term-memory/`,Gradle 子模块名 `:agent:long-term-memory`
- 时间 API:统一用 `java.time.Instant` / `java.time.Duration`,不用 `kotlin.time.Duration`
- frontmatter 解析:不引入 YAML 库,自写最小化解析(只支持 `---` + `key: value` 行)
- IO 抽象:`MemoryStore` 接口 + `PathMemoryStore` 默认实现,IO 走 `Dispatchers.IO`
- 提交规范:遵循 `.claude/rules/git_commit.md`,`chore(build)` / `feat(long-term-memory)` / `test(long-term-memory)` / `refactor(capability)` 等,**只提交不推送**
- API 形态:`explicitApi()` 严格模式,public API 显式标注
- 测试:JUnit 4 + kotlin-test,`runTest` 跑协程测试
- 配置参数命名:`memoryFileLimit`(单文件字符上限)/ `forgetDuration`(遗忘时间,java.time.Duration)

---

## File Structure

```
agent/long-term-memory/
├── build.gradle.kts                                    # Task 2 创建
└── src
    ├── main/kotlin/io/github/yeyi/agent/longtermemory/
    │   ├── MemoryFile.kt           # Task 3:frontmatter 解析/序列化
    │   ├── MemoryStore.kt          # Task 4:存储抽象 + PathMemoryStore
    │   ├── LongTermMemory.kt       # Task 5:Capability 接口 + DefaultLongTermMemory 实现
    │   ├── MemoryContext.kt        # Task 6:Context + Factory
    │   ├── MemoryRegistry.kt       # Task 7:Registry + rescan
    │   ├── SaveMemoryTool.kt       # Task 8:auxiliary 写入工具(不含压缩/遗忘)
    │   ├── MemoryPlugin.kt         # Task 9:Plugin 接线 + user.md 初始化
    │   ├── MemoryExtensions.kt     # Task 9:AgentBuilder.longTermMemory DSL
    │   ├── MemoryCompression.kt    # Task 10:异步压缩
    │   └── MemoryForgetting.kt     # Task 11:遗忘检查(集成到 load/save 路径)
    └── test/kotlin/io/github/yeyi/agent/longtermemory/
        ├── MemoryFileTest.kt
        ├── PathMemoryStoreTest.kt
        ├── LongTermMemoryTest.kt
        ├── MemoryRegistryTest.kt
        ├── SaveMemoryToolTest.kt
        ├── MemoryPluginTest.kt
        └── LongTermMemoryIntegrationTest.kt

agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/
└── CapabilityLoadTool.kt           # Task 1:改 lazy → getter
```

`settings.gradle.kts` 需新增 `include(":agent:long-term-memory")`。

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

## Task 2: 新建 :agent:long-term-memory 模块骨架

**Files:**
- Create: `agent/long-term-memory/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Test: 跑 `./gradlew :agent:long-term-memory:help` 验证模块被识别

**Interfaces:**
- Consumes: 无(空模块)
- Produces: 一个可被 Gradle 识别的空模块,后续 task 填充内容

- [ ] **Step 1: 在 settings.gradle.kts 注册新模块**

修改 `settings.gradle.kts`,在 `include(":agent:skill")` 之后追加:

```kotlin
include(":agent:long-term-memory")
```

- [ ] **Step 2: 创建 build.gradle.kts**

新建 `agent/long-term-memory/build.gradle.kts`,内容仿照 `agent/skill/build.gradle.kts`:

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
mkdir -p agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory
mkdir -p agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory
```

在 `src/main/kotlin/io/github/yeyi/agent/longtermemory/` 下创建占位文件 `package.txt`(空),让 Gradle 不报"无源文件":

```bash
touch agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/package.txt
```

- [ ] **Step 4: 验证模块被 Gradle 识别**

Run: `./gradlew :agent:long-term-memory:help`
Expected: PASS — 输出 help 信息,无错误

- [ ] **Step 5: 提交**

```bash
git add settings.gradle.kts agent/long-term-memory/
git commit -m "chore(long-term-memory): 新建模块骨架

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: MemoryFile —— frontmatter 解析/序列化

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryFile.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryFileTest.kt`

**Interfaces:**
- Consumes: `java.time.Instant`
- Produces:
  - `public data class MemoryFile(val frontmatter: Frontmatter, val body: String)`
  - `public data class MemoryFile.Frontmatter(val name: String, val description: String, val createdAt: Instant, val lastAccessedAt: Instant)`
  - `public companion object { fun parse(content: String): MemoryFile; fun serialize(frontmatter: Frontmatter, body: String): String }`

- [ ] **Step 1: 写失败测试 — 往返序列化**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryFileTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemoryFileTest {

    @Test
    fun `serialize and parse roundtrip preserves frontmatter and body`() {
        val fm = MemoryFile.Frontmatter(
            name = "travel",
            description = "旅行偏好",
            createdAt = Instant.parse("2026-11-25T10:00:00Z"),
            lastAccessedAt = Instant.parse("2026-11-25T14:00:00Z"),
        )
        val body = "# 旅行偏好\n\n用户偏好海岛游。"
        val serialized = MemoryFile.serialize(fm, body)

        val parsed = MemoryFile.parse(serialized)
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

        val parsed = MemoryFile.parse(content)
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

        val parsed = MemoryFile.parse(content)
        assertEquals("user", parsed.frontmatter.name)
        assertEquals("", parsed.body)
    }

    @Test
    fun `serialize empty body produces frontmatter only`() {
        val fm = MemoryFile.Frontmatter(
            name = "user",
            description = "用户画像",
            createdAt = Instant.parse("2026-11-25T10:00:00Z"),
            lastAccessedAt = Instant.parse("2026-11-25T10:00:00Z"),
        )
        val serialized = MemoryFile.serialize(fm, "")
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

        val parsed = MemoryFile.parse(content)
        assertEquals("会议记录:项目进度", parsed.frontmatter.description)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.MemoryFileTest"`
Expected: FAIL — `MemoryFile` 未定义

- [ ] **Step 3: 实现 MemoryFile**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryFile.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import java.time.Instant

/**
 * 长期记忆 md 文件 —— frontmatter 元数据 + Markdown 正文。
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
public data class MemoryFile(
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

        public fun parse(content: String): MemoryFile {
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

            return MemoryFile(
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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.MemoryFileTest"`
Expected: PASS — 5 个测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryFile.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryFileTest.kt
git commit -m "feat(long-term-memory): MemoryFile frontmatter 解析与序列化

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: MemoryStore —— 存储抽象 + PathMemoryStore

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryStore.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/PathMemoryStoreTest.kt`

**Interfaces:**
- Consumes: `java.nio.file.Path`, `kotlinx.coroutines.Dispatchers`
- Produces:
  - `public interface MemoryStore { suspend fun read(name: String): String?; suspend fun write(name: String, content: String); suspend fun delete(name: String); suspend fun exists(name: String): Boolean; suspend fun list(): List<String> }`
  - `public class PathMemoryStore(directory: Path) : MemoryStore`
  - `list()` 排除 `user`(保留文件但不列入)

- [ ] **Step 1: 写失败测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/PathMemoryStoreTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathMemoryStoreTest {

    private val tempDir = Files.createTempDirectory("memory-store-test")
    private val store = PathMemoryStore(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun sampleContent(name: String): String = MemoryFile.serialize(
        MemoryFile.Frontmatter(name, "desc", Instant.parse("2026-11-25T10:00:00Z"), Instant.parse("2026-11-25T10:00:00Z")),
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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.PathMemoryStoreTest"`
Expected: FAIL — `MemoryStore` / `PathMemoryStore` 未定义

- [ ] **Step 3: 实现 MemoryStore + PathMemoryStore**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryStore.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 长期记忆文件存储抽象。默认实现 [PathMemoryStore] 走文件系统,宿主可注入自定义实现。
 *
 * 所有方法为 suspend,允许实现内部走 IO dispatcher。
 * [list] 返回所有 `.md` 文件名(不含后缀),**排除 `user`**(user.md 是特殊保留名,不作为普通话题注册)。
 */
public interface MemoryStore {
    public suspend fun read(name: String): String?
    public suspend fun write(name: String, content: String)
    public suspend fun delete(name: String)
    public suspend fun exists(name: String): Boolean
    public suspend fun list(): List<String>
}

/**
 * 基于 [Path] 的 [MemoryStore] 默认实现。
 *
 * - 文件路径:`$directory/$name.md`
 * - IO 走 [Dispatchers.IO]
 * - 构造时自动创建目录
 * - [list] 排除 `user.md`(保留文件但不作为话题注册)
 */
public class PathMemoryStore(private val directory: Path) : MemoryStore {
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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.PathMemoryStoreTest"`
Expected: PASS — 7 个测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryStore.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/PathMemoryStoreTest.kt
git commit -m "feat(long-term-memory): MemoryStore 抽象与 PathMemoryStore 默认实现

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: LongTermMemory —— Capability 接口 + Default 实现

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemory.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryTest.kt`

**Interfaces:**
- Consumes: `Capability<Unit, MemoryContext>`(来自 `:agent:capability`)、`MemoryStore`、`MemoryFile`(Task 3/4)
- Produces:
  - `public interface LongTermMemory : Capability<Unit, MemoryContext>`(含 `load()` 抽象方法 + `CAPABILITY_TYPE = "memory"` companion)
  - `internal class DefaultLongTermMemory(...)` — 持久化 + lastAccessedAt 更新(暂不含遗忘检查,Task 11 加)

**注意:** 本 task 暂不引入 `MemoryContext`,因为 `MemoryContext` 在 Task 6 创建。本 task 的 `DefaultLongTermMemory.load()` 直接用构造器传入的 `MemoryStore`,不依赖 context 字段。`activate` 调 `load()` 时 context 参数忽略。

- [ ] **Step 1: 写失败测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

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

class LongTermMemoryTest {

    private val tempDir = Files.createTempDirectory("ltm-test")
    private val store = PathMemoryStore(tempDir)

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun seedMemory(name: String, body: String, lastAccessedAt: Instant = Instant.parse("2026-11-25T10:00:00Z")) {
        val content = MemoryFile.serialize(
            MemoryFile.Frontmatter(name, "desc for $name", Instant.parse("2026-01-01T00:00:00Z"), lastAccessedAt),
            body,
        )
        store.writeSync(name, content)
    }

    private fun MemoryStore.writeSync(name: String, content: String) {
        kotlinx.coroutines.runBlocking { write(name, content) }
    }

    @Test
    fun `CAPABILITY_TYPE is memory`() {
        assertEquals("memory", LongTermMemory.CAPABILITY_TYPE)
    }

    @Test
    fun `load returns full md content and updates lastAccessedAt`() = runTest {
        seedMemory("travel", "去东南亚")
        val before = Instant.now()
        val memory = DefaultLongTermMemory("travel", "desc for travel", store)
        val content = memory.load()
        val after = Instant.now()

        // 内容含正文
        assertTrue(content.contains("去东南亚"))
        assertTrue(content.contains("name: travel"))

        // lastAccessedAt 已写回文件,且在 [before, after] 区间
        val raw = store.read("travel")!!
        val parsed = MemoryFile.parse(raw)
        assertTrue(!parsed.frontmatter.lastAccessedAt.isBefore(before))
        assertTrue(!parsed.frontmatter.lastAccessedAt.isAfter(after))
    }

    @Test
    fun `activate delegates to load`() = runTest {
        seedMemory("food", "喜欢吃辣")
        val memory = DefaultLongTermMemory("food", "desc for food", store)
        val result = memory.activate(null, stubMemoryContext())
        assertTrue(result.contains("喜欢吃辣"))
    }

    @Test
    fun `name and description come from constructor`() {
        val m = DefaultLongTermMemory("topic", "my desc", store)
        assertEquals("topic", m.name)
        assertEquals("my desc", m.description)
    }

    // Stub MemoryContext for tests (real one comes in Task 6)
    private fun stubMemoryContext(): MemoryContext {
        val llmProvider = object : LlmProvider {
            override val name: String = "stub"
            override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
                throw UnsupportedOperationException()
            override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): Flow<io.github.yeyi.agent.llm.ChatResponseEvent> =
                throw UnsupportedOperationException()
        }
        return MemoryContext(
            memoryStore = store,
            llmProvider = llmProvider,
            registry = MemoryRegistry(store, 8000, java.time.Duration.ofDays(30), llmProvider),
            memoryFileLimit = 8000,
            forgetDuration = java.time.Duration.ofDays(30),
        )
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.LongTermMemoryTest"`
Expected: FAIL — `LongTermMemory` / `DefaultLongTermMemory` / `MemoryContext` / `MemoryRegistry` 未定义

- [ ] **Step 3: 实现 LongTermMemory + DefaultLongTermMemory**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemory.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.capability.Capability
import java.time.Instant

/**
 * 一个长期记忆话题 —— 对应根目录下的一个 `.md` 文件。
 *
 * 实现 [Capability] 接口,通过 `CapabilityLoadTool` 暴露为 `load_memory` 工具的可路由条目。
 * LLM 调用 `load_memory(name=...)` 时,框架按 name 路由到对应实例,调 [load] 返回 md 全文。
 *
 * 与 Skill 的对称点:
 * - 都是 `Capability<Unit, Ctx>`,load() 返回文本注入 LLM 上下文
 * - 都是"按需加载",description 告诉 LLM 何时使用,正文不预加载
 *
 * 与 Skill 的区别:
 * - Skill 是静态注册,LongTermMemory 由 [MemoryRegistry] 在 install/rescan 时动态构建
 * - load() 触发 lastAccessedAt 更新和遗忘检查
 */
public interface LongTermMemory : Capability<Unit, MemoryContext> {
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

    override suspend fun activate(arguments: Unit?, context: MemoryContext): String = load()

    public companion object {
        internal const val CAPABILITY_TYPE: String = "memory"
    }
}

/**
 * 默认实现 —— 持有 [memoryStore] 与 [name],[load] 时读文件 + 更新 lastAccessedAt。
 */
internal class DefaultLongTermMemory(
    override val name: String,
    override val description: String,
    private val memoryStore: MemoryStore,
) : LongTermMemory {
    override suspend fun load(): String {
        val raw = memoryStore.read(name) ?: return "(memory expired)"
        val (frontmatter, body) = MemoryFile.parse(raw)
        val updated = frontmatter.copy(lastAccessedAt = Instant.now())
        memoryStore.write(name, MemoryFile.serialize(updated, body))
        return raw // 返回原始内容(包含旧 frontmatter);LLM 看到的是当时的快照
    }
}
```

**注:** `load()` 返回的是更新前的原始 md 全文(LLM 看到的快照是 load 那一刻的内容),`lastAccessedAt` 更新是写回文件的副作用,不进入返回值。

- [ ] **Step 4: 跑测试验证失败(MemoryContext / MemoryRegistry 还没建)**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.LongTermMemoryTest"`
Expected: FAIL — 仍是 `MemoryContext` / `MemoryRegistry` 未定义

**这是预期的** — 测试中 stubMemoryContext 引用了 Task 6 才会创建的 `MemoryContext` 和 `MemoryRegistry`。先继续 Task 6,完成后回到本 task 跑测试。

- [ ] **Step 5: 暂不提交(等 Task 6 完成后合并提交)**

---

## Task 6: MemoryContext —— 执行上下文 + Factory

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryContext.kt`
- Test: 本 task 不单独写测试,在 Task 5 测试中已 stub,Task 5/6 完成后一起验证

**Interfaces:**
- Consumes: `CapabilityContext` / `CapabilityContextFactory` / `ToolExecutionContext` / `LlmProvider`
- Produces:
  - `public class MemoryContext(memoryStore, llmProvider, registry, memoryFileLimit, forgetDuration) : CapabilityContext`
  - `internal class MemoryContextFactory(...) : CapabilityContextFactory<MemoryContext>`
  - `MemoryContext.llmProvider` 字段类型为 `LlmProvider`(非 null,因 factory 已做 null 回退)

- [ ] **Step 1: 实现 MemoryContext + Factory**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryContext.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

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
 * - [registry]: 用于 save_memory 创建新话题后触发 rescan
 * - [memoryFileLimit]: 单文件字符上限,超则异步压缩
 * - [forgetDuration]: 遗忘时间,文件超过此时长未被读写则删
 */
public class MemoryContext(
    public val memoryStore: MemoryStore,
    public val llmProvider: LlmProvider,
    public val registry: MemoryRegistry,
    public val memoryFileLimit: Int,
    public val forgetDuration: Duration,
) : CapabilityContext

/**
 * 桥接 [ToolExecutionContext] 到 [MemoryContext]。
 *
 * llmProvider 为 null 时回退到 [ToolExecutionContext.agentContext.llmProvider]。
 */
internal class MemoryContextFactory(
    private val memoryStore: MemoryStore,
    private val llmProvider: LlmProvider?,
    private val registry: MemoryRegistry,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
) : CapabilityContextFactory<MemoryContext> {
    override fun create(context: ToolExecutionContext): MemoryContext {
        val effectiveLlmProvider = llmProvider ?: context.agentContext.llmProvider
        return MemoryContext(memoryStore, effectiveLlmProvider, registry, memoryFileLimit, forgetDuration)
    }
}
```

- [ ] **Step 2: 暂不跑测试(还差 MemoryRegistry,Task 7 完成)**

---

## Task 7: MemoryRegistry —— 注册中心 + rescan

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistry.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistryTest.kt`

**Interfaces:**
- Consumes: `CapabilityRegistry<LongTermMemory, Unit, MemoryContext>` / `DefaultCapabilityRegistry` / `MemoryStore` / `MemoryFile`
- Produces:
  - `public class MemoryRegistry(memoryStore, memoryFileLimit, forgetDuration, llmProvider) : CapabilityRegistry<LongTermMemory, Unit, MemoryContext>`
  - `public suspend fun rescan()` — 扫描目录,同步 registry 与文件系统状态

- [ ] **Step 1: 写失败测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistryTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

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

class MemoryRegistryTest {

    private val tempDir = Files.createTempDirectory("registry-test")
    private val store = PathMemoryStore(tempDir)
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
        store.write(name, MemoryFile.serialize(
            MemoryFile.Frontmatter(name, description, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")),
            "body",
        ))
    }

    @Test
    fun `rescan picks up new files`() = runTest {
        seedFile("travel", "旅行")
        seedFile("food", "美食")
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("travel", "food"), names)
    }

    @Test
    fun `rescan removes entries for deleted files`() = runTest {
        seedFile("travel", "旅行")
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        store.delete("travel")
        registry.rescan()
        assertTrue(registry.all().none { it.name == "travel" })
    }

    @Test
    fun `rescan adds new files created after initial scan`() = runTest {
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        assertTrue(registry.all().isEmpty())

        seedFile("travel", "旅行")
        registry.rescan()
        assertEquals(1, registry.all().size)
        assertEquals("travel", registry.all().first().name)
    }

    @Test
    fun `rescan does not include user file`() = runTest {
        store.write("user", MemoryFile.serialize(
            MemoryFile.Frontmatter("user", "用户画像", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")),
            "user body",
        ))
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        registry.rescan()
        assertTrue(registry.all().none { it.name == "user" })
    }

    @Test
    fun `capabilityType is memory`() {
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        assertEquals("memory", registry.capabilityType)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.MemoryRegistryTest"`
Expected: FAIL — `MemoryRegistry` 未定义

- [ ] **Step 3: 实现 MemoryRegistry**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistry.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.llm.LlmProvider
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 长期记忆注册中心,负责 (re)scan 目录、维护 name → LongTermMemory 映射。
 *
 * - [rescan] 扫描根目录所有 `.md` 文件(由 [MemoryStore.list] 返回,已排除 `user.md`),
 *   解析 frontmatter,同步 registry 与文件系统状态。
 * - 已删除的文件从 registry 移除;新增的文件加入 registry;变更的 frontmatter 反映到 description。
 * - 线程安全:rescan 与 register 互斥(用 [Mutex])。
 */
public class MemoryRegistry(
    private val memoryStore: MemoryStore,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
    private val llmProvider: LlmProvider?,
) : CapabilityRegistry<LongTermMemory, Unit, MemoryContext> by DefaultCapabilityRegistry(
    capabilityType = LongTermMemory.CAPABILITY_TYPE
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

    private suspend fun loadFromStore(name: String): LongTermMemory? {
        val content = memoryStore.read(name) ?: return null
        return try {
            val (frontmatter, _) = MemoryFile.parse(content)
            DefaultLongTermMemory(frontmatter.name, frontmatter.description, memoryStore)
        } catch (e: Exception) {
            null // 解析失败的文件跳过
        }
    }
}
```

- [ ] **Step 4: 跑 Task 5/6/7 所有测试验证通过**

Run: `./gradlew :agent:long-term-memory:test`
Expected: PASS — LongTermMemoryTest、MemoryRegistryTest 全部通过

- [ ] **Step 5: 提交 Task 5/6/7**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemory.kt \
        agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryContext.kt \
        agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistry.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryTest.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryRegistryTest.kt
git commit -m "feat(long-term-memory): LongTermMemory/MemoryContext/MemoryRegistry 核心类型

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: SaveMemoryTool —— 写入工具(不含压缩/遗忘)

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt`

**Interfaces:**
- Consumes: `Tool` / `ToolExecutionResult` / `ToolParameters` / `MemoryStore` / `MemoryFile` / `MemoryRegistry` / `CapabilityContextFactory<MemoryContext>`
- Produces:
  - `internal class SaveMemoryTool(registry, contextFactory, compressScope) : Tool`
  - 工具名 `save_memory`,参数 `{name, content, mode}`
  - 副作用:新话题创建触发 `rescan`,已有话题更新 `lastAccessedAt`,异步压缩入口(本 task 占位,Task 10 实现)

**注意:** 本 task 不实现压缩与遗忘检查,只跑通读写路径。Task 10/11 会扩展。

- [ ] **Step 1: 写失败测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.ToolExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

class SaveMemoryToolTest {

    private val tempDir = Files.createTempDirectory("save-tool-test")
    private val store = PathMemoryStore(tempDir)
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant("summary"),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    private val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
    private val ctxFactory = object : CapabilityContextFactory<MemoryContext> {
        override fun create(context: ToolExecutionContext) = MemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = registry,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
        )
    }
    private val compressScope = CoroutineScope(SupervisorJob())
    private val tool = SaveMemoryTool(registry, ctxFactory, compressScope)

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

    private fun args(name: String, content: String, mode: String = "append"): JsonObject = JsonObject(mapOf(
        "name" to JsonPrimitive(name),
        "content" to JsonPrimitive(content),
        "mode" to JsonPrimitive(mode),
    ))

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
        store.write("travel", MemoryFile.serialize(
            MemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "原内容",
        ))
        registry.rescan()
        tool.execute(args("travel", "新内容", "append"), execContext())
        val parsed = MemoryFile.parse(store.read("travel")!!)
        assertEquals("原内容\n\n新内容", parsed.body)
    }

    @Test
    fun `replace overwrites body`() = runTest {
        store.write("travel", MemoryFile.serialize(
            MemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "原内容",
        ))
        registry.rescan()
        tool.execute(args("travel", "替换后", "replace"), execContext())
        val parsed = MemoryFile.parse(store.read("travel")!!)
        assertEquals("替换后", parsed.body)
    }

    @Test
    fun `save updates lastAccessedAt`() = runTest {
        val oldTime = java.time.Instant.parse("2026-01-01T00:00:00Z")
        store.write("travel", MemoryFile.serialize(
            MemoryFile.Frontmatter("travel", "旅行", oldTime, oldTime),
            "原内容",
        ))
        registry.rescan()
        val before = java.time.Instant.now()
        tool.execute(args("travel", "追加", "append"), execContext())
        val after = java.time.Instant.now()
        val parsed = MemoryFile.parse(store.read("travel")!!)
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
        val parsed = MemoryFile.parse(store.read("user")!!)
        assertEquals("用户画像:开发者", parsed.body)
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.SaveMemoryToolTest"`
Expected: FAIL — `SaveMemoryTool` 未定义

- [ ] **Step 3: 实现 SaveMemoryTool(不含压缩/遗忘)**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM 写入长期记忆的工具。
 *
 * 参数:
 * - name: 话题名(对应文件名,不含 .md)
 * - content: 写入内容(markdown)
 * - mode: "append" 追加到正文末尾 / "replace" 覆盖整个正文(frontmatter 保留)
 *
 * 副作用:
 * - 若 name 是新话题,创建文件并触发 registry.rescan()
 * - 更新 frontmatter.lastAccessedAt
 * - (Task 10/11 加)遗忘检查 + 异步压缩
 *
 * 返回:写入确认信息
 */
internal class SaveMemoryTool(
    private val registry: MemoryRegistry,
    private val contextFactory: CapabilityContextFactory<MemoryContext>,
    private val compressScope: CoroutineScope,
) : Tool {
    override val name: String = "save_memory"

    override val description: String = """
        将信息写入长期记忆。当用户告诉你值得跨会话记住的事实(偏好、背景、约定等)时调用本工具。

        参数:
        - name: 话题名(英文短词,如 "travel-preferences")。若话题已存在则更新;若不存在则新建。
        - content: markdown 内容。append 模式下追加到现有内容末尾;replace 模式下覆盖正文(frontmatter 保留)。
        - mode: "append" 或 "replace",默认 "append"。

        特殊话题:
        - "user": 写入用户画像,每轮会主动注入 system prompt,不会遗忘。
    """.trimIndent()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","replace"]}},"required":["name","content"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val obj = arguments.jsonObject
        val name = (obj["name"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        val content = (obj["content"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'content'")
        val mode = (obj["mode"] as? JsonPrimitive)?.content ?: "append"

        val ctx = contextFactory.create(context)
        val store = ctx.memoryStore
        val now = Instant.now()

        val isNew = !store.exists(name)
        val (frontmatter, body) = if (isNew) {
            MemoryFile.Frontmatter(name, descriptionFromContent(content), now, now) to ""
        } else {
            val raw = store.read(name)!!
            val (fm, b) = MemoryFile.parse(raw)
            fm.copy(lastAccessedAt = now) to b
        }

        val newBody = when (mode) {
            "append" -> if (body.isBlank()) content else "$body\n\n$content"
            "replace" -> content
            else -> return ToolExecutionResult.error("Invalid mode: $mode")
        }
        store.write(name, MemoryFile.serialize(frontmatter, newBody))

        if (isNew) registry.rescan()

        // 异步压缩入口(Task 10 实现)
        if (newBody.length > ctx.memoryFileLimit) {
            compressScope.launch {
                runCatching {
                    compressMemory(ctx, name, frontmatter, newBody)
                }
            }
        }

        return ToolExecutionResult.success("Saved memory '$name' (${newBody.length} chars).")
    }

    private suspend fun compressMemory(
        ctx: MemoryContext,
        name: String,
        frontmatter: MemoryFile.Frontmatter,
        body: String,
    ) {
        // Task 10 实现
    }

    private fun descriptionFromContent(content: String): String =
        content.lineSequence().firstOrNull()?.removePrefix("#")?.trim()?.take(80)?.ifBlank { "Long-term memory" }
            ?: "Long-term memory"
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.SaveMemoryToolTest"`
Expected: PASS — 8 个测试全部通过

- [ ] **Step 5: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt
git commit -m "feat(long-term-memory): SaveMemoryTool 写入工具(append/replace)

不含压缩与遗忘,Task 10/11 扩展。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: MemoryPlugin + DSL —— 接线与 user.md 初始化

**Files:**
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryPlugin.kt`
- Create: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryExtensions.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryPluginTest.kt`

**Interfaces:**
- Consumes: `AgentPlugin` / `AgentPluginContext` / `CapabilityAdapter` / `MemoryStore` / `MemoryRegistry` / `SaveMemoryTool` / `MemoryFile`
- Produces:
  - `public object MemoryPluginConfig` — placeholder config 类型(满足 `AgentPlugin<C : Any>` 约束)
  - `internal class MemoryPlugin(...) : AgentPlugin<MemoryPluginConfig>`
  - `public fun AgentBuilder.longTermMemory(directory, memoryFileLimit, forgetDuration, llmProvider, memoryStore, enableDelegateAdaptMode)`

- [ ] **Step 1: 写失败测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryPluginTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

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

class MemoryPluginTest {

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
        val store = PathMemoryStore(tempDir)
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = MemoryPlugin(
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
        val parsed = MemoryFile.parse(store.read("user")!!)
        assertEquals("user", parsed.frontmatter.name)
        assertEquals("", parsed.body)
    }

    @Test
    fun `install does not overwrite existing user md`() = runTest {
        val store = PathMemoryStore(tempDir)
        val existing = MemoryFile.serialize(
            MemoryFile.Frontmatter("user", "已存在", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "已有内容",
        )
        store.write("user", existing)

        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = MemoryPlugin(
            directory = tempDir,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
            llmProvider = stubLlm,
            memoryStore = store,
            enableDelegateAdaptMode = true,
            registry = registry,
        )
        plugin.install(FakePluginContext())
        val parsed = MemoryFile.parse(store.read("user")!!)
        assertEquals("已有内容", parsed.body)
    }

    @Test
    fun `install injects user body to instructions when non-blank`() = runTest {
        val store = PathMemoryStore(tempDir)
        store.write("user", MemoryFile.serialize(
            MemoryFile.Frontmatter("user", "用户画像", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "用户是开发者",
        ))
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = MemoryPlugin(
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
    fun `install registers load_memory and save_memory tools`() = runTest {
        val store = PathMemoryStore(tempDir)
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = MemoryPlugin(
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
        assertTrue("load_memory" in names)
        assertTrue("save_memory" in names)
    }

    @Test
    fun `install picks up existing topic files via rescan`() = runTest {
        val store = PathMemoryStore(tempDir)
        store.write("travel", MemoryFile.serialize(
            MemoryFile.Frontmatter("travel", "旅行", java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2026-01-01T00:00:00Z")),
            "body",
        ))
        val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
        val plugin = MemoryPlugin(
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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.MemoryPluginTest"`
Expected: FAIL — `MemoryPlugin` / `MemoryExtensions` 未定义

- [ ] **Step 3: 实现 MemoryPlugin + MemoryPluginConfig**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryPlugin.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.capability.CapabilityAdapter
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * 占位 config 类型,满足 [AgentPlugin] 的 `C : Any` 约束。
 *
 * MemoryPlugin 的所有配置在构造器完成,configure block 为 no-op。
 */
public object MemoryPluginConfig

/**
 * 长期记忆插件的接线类。
 *
 * 直接实现 [AgentPlugin] 而非继承 [io.github.yeyi.agent.capability.CapabilityPlugin],
 * 因为需要在 install 阶段调 [AgentPluginContext.appendInstructions] 注入 user.md ——
 * `CapabilityPlugin.install` 是 `final override` 且不调 `appendInstructions`。
 *
 * 仍复用 [CapabilityAdapter.of] 拼装 load_memory(委托模式),与 Skill 形态对称。
 *
 * install 流程:
 * 1. ensureUserMemory: 若 `user.md` 不存在,创建空模板
 * 2. registry.rescan(): 扫描所有话题文件,构建内存注册表
 * 3. 读 user.md 正文,通过 appendInstructions 注入 persona 能力区
 * 4. CapabilityAdapter.of(registry, ctxFactory, null, enableDelegateAdaptMode).adapt()
 *    → load_memory 工具,registerTool
 * 5. registerTool(save_memory)
 */
internal class MemoryPlugin(
    private val directory: Path,
    private val memoryFileLimit: Int,
    private val forgetDuration: Duration,
    private val llmProvider: LlmProvider?,
    private val memoryStore: MemoryStore,
    private val enableDelegateAdaptMode: Boolean,
    private val registry: MemoryRegistry,
) : AgentPlugin<MemoryPluginConfig> {

    override val id: String = "long-term-memory"

    private val compressScope = CoroutineScope(SupervisorJob())
    private val ctxFactory: CapabilityContextFactory<MemoryContext> = MemoryContextFactory(
        memoryStore = memoryStore,
        llmProvider = llmProvider,
        registry = registry,
        memoryFileLimit = memoryFileLimit,
        forgetDuration = forgetDuration,
    )

    override fun configure(block: MemoryPluginConfig.() -> Unit) {
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
            val (_, body) = MemoryFile.parse(userRaw)
            if (body.isNotBlank()) {
                context.appendInstructions(body)
            }
        }

        // 注册 load_memory(委托模式,继承 CapabilityLoadTool)
        CapabilityAdapter.of(registry, ctxFactory, null, enableDelegateAdaptMode)
            .adapt()
            .forEach { context.registerTool(it) }

        // 注册 save_memory
        context.registerTool(SaveMemoryTool(registry, ctxFactory, compressScope))
    }

    private suspend fun ensureUserMemory() {
        if (!memoryStore.exists("user")) {
            val now = Instant.now()
            val empty = MemoryFile.serialize(
                MemoryFile.Frontmatter(
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

- [ ] **Step 4: 实现 MemoryExtensions**

新建 `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryExtensions.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path
import java.time.Duration
import java.time.Duration.Companion.days

/**
 * 注册长期记忆插件到 Agent。
 *
 * @param directory 记忆文件根目录,宿主必传
 * @param memoryFileLimit 单文件字符上限,超则异步压缩。默认 8000
 * @param forgetDuration 遗忘时间,文件超过此时长未被读写则删。默认 30 天
 * @param llmProvider 压缩用 LLM;null 时回退到 AgentContext.llmProvider。默认 null
 * @param memoryStore 文件 IO 抽象;null 时用 [PathMemoryStore]。默认 null
 * @param enableDelegateAdaptMode load_memory 是否走委托模式(单一工具 + name 路由)。默认 true
 */
public fun AgentBuilder.longTermMemory(
    directory: Path,
    memoryFileLimit: Int = 8000,
    forgetDuration: Duration = 30.days,
    llmProvider: LlmProvider? = null,
    memoryStore: MemoryStore? = null,
    enableDelegateAdaptMode: Boolean = true,
) {
    val store = memoryStore ?: PathMemoryStore(directory)
    val registry = MemoryRegistry(store, memoryFileLimit, forgetDuration, llmProvider)
    val plugin = MemoryPlugin(
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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.MemoryPluginTest"`
Expected: PASS — 5 个测试全部通过

- [ ] **Step 6: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryPlugin.kt \
        agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/MemoryExtensions.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/MemoryPluginTest.kt
git commit -m "feat(long-term-memory): MemoryPlugin 接线 + AgentBuilder DSL

- install 时创建 user.md 空模板,rescan 话题,appendInstructions 注入 user 正文
- 通过 CapabilityAdapter.of 拼装 load_memory,auxiliary 注册 save_memory
- MemoryPluginConfig object 满足 AgentPlugin<C : Any> 约束

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: 异步压缩

**Files:**
- Modify: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt`
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt`(追加压缩测试)

**Interfaces:**
- Consumes: `MemoryContext.llmProvider` / `ChatRequest` / `ChatMessage.System` / `ChatResponse.message.content`
- Produces: `SaveMemoryTool.compressMemory` 实现完成,save 后超 `memoryFileLimit` 自动异步压缩

- [ ] **Step 1: 写失败测试 — 压缩被触发且文件被覆盖**

在 `SaveMemoryToolTest.kt` 末尾追加:

```kotlin
@Test
fun `save triggers async compression when body exceeds memoryFileLimit`() = runTest {
    // 用一个会返回"compressed"的 stub LLM
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
    val compressingRegistry = MemoryRegistry(store, smallLimit, Duration.ofDays(30), compressingLlm)
    val compressingCtxFactory = object : CapabilityContextFactory<MemoryContext> {
        override fun create(context: ToolExecutionContext) = MemoryContext(
            memoryStore = store,
            llmProvider = compressingLlm,
            registry = compressingRegistry,
            memoryFileLimit = smallLimit,
            forgetDuration = Duration.ofDays(30),
        )
    }
    val compressingTool = SaveMemoryTool(compressingRegistry, compressingCtxFactory, compressScope)

    // 写入超限内容
    val longContent = "这是一个很长的内容,超过 10 字符上限"
    compressingTool.execute(args("long-topic", longContent, "replace"), execContext())

    // 等待异步压缩完成
    kotlinx.coroutines.delay(500)

    val parsed = MemoryFile.parse(store.read("long-topic")!!)
    assertEquals("压缩后内容", parsed.body)
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.SaveMemoryToolTest.save triggers async compression when body exceeds memoryFileLimit"`
Expected: FAIL — body 仍是原始长内容(`compressMemory` 是空实现)

- [ ] **Step 3: 实现 compressMemory**

修改 `SaveMemoryTool.kt` 的 `compressMemory`:

```kotlin
private suspend fun compressMemory(
    ctx: MemoryContext,
    name: String,
    frontmatter: MemoryFile.Frontmatter,
    body: String,
) {
    val targetSize = ctx.memoryFileLimit / 2
    val prompt = """
        请总结以下长期记忆内容,保留关键信息,压缩到 $targetSize 字符以内。
        只输出压缩后的正文,不要任何前缀说明。

        原始内容:
        $body
    """.trimIndent()

    val request = io.github.yeyi.agent.llm.ChatRequest(
        messages = listOf(io.github.yeyi.agent.llm.ChatMessage.System(prompt)),
    )
    val response = ctx.llmProvider.chat(request)
    val summary = response.message.content ?: body // 失败回退为原文

    ctx.memoryStore.write(name, MemoryFile.serialize(frontmatter, summary))
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.SaveMemoryToolTest"`
Expected: PASS — 9 个测试全部通过(含新增压缩测试)

- [ ] **Step 5: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt
git commit -m "feat(long-term-memory): 异步压缩,超 memoryFileLimit 时调 LLM 总结

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: 遗忘检查(集成到 load/save 路径)

**Files:**
- Modify: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemory.kt`(load 时检查)
- Modify: `agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt`(save 时检查)
- Test: 扩展 `LongTermMemoryTest.kt` 和 `SaveMemoryToolTest.kt`

**Interfaces:**
- Consumes: `MemoryContext.forgetDuration` / `MemoryContext.memoryStore` / `MemoryContext.registry`
- Produces: load/save 路径在操作前检查 `lastAccessedAt`,超阈值则删除文件 + rescan

- [ ] **Step 1: 写失败测试 — load 时遗忘过期文件**

在 `LongTermMemoryTest.kt` 末尾追加:

```kotlin
@Test
fun `load deletes file when lastAccessedAt exceeds forgetDuration`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60) // 60 秒前
    seedMemory("old-topic", "旧内容", lastAccessedAt = oldTime)
    val shortForgetDuration = java.time.Duration.ofSeconds(30) // 30 秒就遗忘

    val memory = DefaultLongTermMemory("old-topic", "desc for old-topic", store)
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
    val registry = MemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val ctx = MemoryContext(store, stubLlm, registry, 8000, shortForgetDuration)

    val result = memory.activate(null, ctx)
    assertEquals("(memory expired)", result)
    assertFalse(store.exists("old-topic"))
}

@Test
fun `load does not forget user file even when expired`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60)
    seedMemory("user", "用户画像", lastAccessedAt = oldTime)
    val shortForgetDuration = java.time.Duration.ofSeconds(30)

    val memory = DefaultLongTermMemory("user", "用户画像", store)
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
    val registry = MemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val ctx = MemoryContext(store, stubLlm, registry, 8000, shortForgetDuration)

    memory.activate(null, ctx)
    assertTrue(store.exists("user")) // user 不被遗忘
}
```

- [ ] **Step 2: 写失败测试 — save 时遗忘过期文件**

在 `SaveMemoryToolTest.kt` 末尾追加:

```kotlin
@Test
fun `save deletes file when lastAccessedAt exceeds forgetDuration`() = runTest {
    val oldTime = java.time.Instant.now().minusSeconds(60)
    store.write("old-topic", MemoryFile.serialize(
        MemoryFile.Frontmatter("old-topic", "旧", oldTime, oldTime),
        "原内容",
    ))
    val shortForgetDuration = Duration.ofSeconds(30)
    val shortForgetRegistry = MemoryRegistry(store, 8000, shortForgetDuration, stubLlm)
    val shortForgetCtxFactory = object : CapabilityContextFactory<MemoryContext> {
        override fun create(context: ToolExecutionContext) = MemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = shortForgetRegistry,
            memoryFileLimit = 8000,
            forgetDuration = shortForgetDuration,
        )
    }
    val shortForgetTool = SaveMemoryTool(shortForgetRegistry, shortForgetCtxFactory, compressScope)
    shortForgetRegistry.rescan()

    val result = shortForgetTool.execute(args("old-topic", "新内容", "append"), execContext())
    assertTrue(result.isError.not())
    assertTrue(result.parts.first().let { it is io.github.yeyi.agent.llm.ContentPart.Text && it.text.contains("expired") })
    assertFalse(store.exists("old-topic"))
}
```

- [ ] **Step 3: 跑测试验证失败**

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.LongTermMemoryTest.load deletes file when lastAccessedAt exceeds forgetDuration" --tests "io.github.yeyi.agent.longtermemory.SaveMemoryToolTest.save deletes file when lastAccessedAt exceeds forgetDuration"`
Expected: FAIL — 遗忘逻辑未实现

- [ ] **Step 4: 改 DefaultLongTermMemory.load 集成遗忘检查**

修改 `LongTermMemory.kt` 的 `DefaultLongTermMemory`:

```kotlin
internal class DefaultLongTermMemory(
    override val name: String,
    override val description: String,
    private val memoryStore: MemoryStore,
) : LongTermMemory {
    override suspend fun load(): String = "(load should not be called without context)"

    override suspend fun activate(arguments: Unit?, context: MemoryContext): String {
        val raw = memoryStore.read(name) ?: return "(memory expired)"
        val (frontmatter, body) = MemoryFile.parse(raw)
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
        memoryStore.write(name, MemoryFile.serialize(updated, body))
        return raw
    }
}
```

**注:** 原来的 `load()` 抽象方法保留(满足接口),但实际逻辑移到 `activate` —— 因为遗忘检查需要 `MemoryContext.forgetDuration`,而 `load()` 无参。`activate(null, context)` 是 `CapabilityLoadTool.execute` 的实际入口,会传 context。`load()` 改为返回错误提示,防止误调。

- [ ] **Step 5: 改 SaveMemoryTool.execute 集成遗忘检查**

修改 `SaveMemoryTool.kt` 的 `execute`,在 `frontmatter, body` 解析后、`newBody` 计算前插入遗忘检查:

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
    val content = (obj["content"] as? JsonPrimitive)?.content
        ?: return ToolExecutionResult.error("Missing 'content'")
    val mode = (obj["mode"] as? JsonPrimitive)?.content ?: "append"

    val ctx = contextFactory.create(context)
    val store = ctx.memoryStore
    val now = Instant.now()

    val isNew = !store.exists(name)
    val (frontmatter, body) = if (isNew) {
        MemoryFile.Frontmatter(name, descriptionFromContent(content), now, now) to ""
    } else {
        val raw = store.read(name)!!
        val (fm, b) = MemoryFile.parse(raw)
        fm.copy(lastAccessedAt = now) to b
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

    val newBody = when (mode) {
        "append" -> if (body.isBlank()) content else "$body\n\n$content"
        "replace" -> content
        else -> return ToolExecutionResult.error("Invalid mode: $mode")
    }
    store.write(name, MemoryFile.serialize(frontmatter, newBody))

    if (isNew) registry.rescan()

    if (newBody.length > ctx.memoryFileLimit) {
        compressScope.launch {
            runCatching {
                compressMemory(ctx, name, frontmatter, newBody)
            }
        }
    }

    return ToolExecutionResult.success("Saved memory '$name' (${newBody.length} chars).")
}
```

**注意:** `frontmatter.copy(lastAccessedAt = now)` 已在 else 分支执行,所以遗忘检查时 `frontmatter.lastAccessedAt` 是更新后的值。这不对——遗忘检查应该看的是旧值。修正:`else` 分支不更新 `lastAccessedAt`,而是在遗忘检查通过后再更新。

修正后的 `else` 分支:

```kotlin
} else {
    val raw = store.read(name)!!
    val (fm, b) = MemoryFile.parse(raw)
    fm to b  // 保留原 lastAccessedAt 用于遗忘检查
}
```

然后遗忘检查通过后,在 `store.write` 之前更新 lastAccessedAt:

```kotlin
// 通过遗忘检查后,更新 lastAccessedAt
val updatedFrontmatter = frontmatter.copy(lastAccessedAt = now)
// ... 计算 newBody ...
store.write(name, MemoryFile.serialize(updatedFrontmatter, newBody))
```

完整修正版 `execute`:

```kotlin
override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
    val obj = arguments.jsonObject
    val name = (obj["name"] as? JsonPrimitive)?.content
        ?: return ToolExecutionResult.error("Missing 'name'")
    val content = (obj["content"] as? JsonPrimitive)?.content
        ?: return ToolExecutionResult.error("Missing 'content'")
    val mode = (obj["mode"] as? JsonPrimitive)?.content ?: "append"

    val ctx = contextFactory.create(context)
    val store = ctx.memoryStore
    val now = Instant.now()

    val isNew = !store.exists(name)
    val (frontmatter, body) = if (isNew) {
        MemoryFile.Frontmatter(name, descriptionFromContent(content), now, now) to ""
    } else {
        val raw = store.read(name)!!
        val (fm, b) = MemoryFile.parse(raw)
        fm to b
    }

    // 遗忘检查:user 豁免;基于原始 lastAccessedAt
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
        "replace" -> content
        else -> return ToolExecutionResult.error("Invalid mode: $mode")
    }
    store.write(name, MemoryFile.serialize(updatedFrontmatter, newBody))

    if (isNew) registry.rescan()

    if (newBody.length > ctx.memoryFileLimit) {
        compressScope.launch {
            runCatching {
                compressMemory(ctx, name, updatedFrontmatter, newBody)
            }
        }
    }

    return ToolExecutionResult.success("Saved memory '$name' (${newBody.length} chars).")
}
```

- [ ] **Step 6: 同步修正 Task 5 测试中的 activate 调用**

Task 5 测试里的 `memory.activate(null, stubMemoryContext())` 仍可用,但 `stubMemoryContext()` 的 `forgetDuration` 是 30 天,seedMemory 用的 `lastAccessedAt = Instant.parse("2026-11-25T10:00:00Z")` 在 30 天内,不会触发遗忘。如果测试跑得离 2026-11-25 太远(超过 30 天),会触发遗忘——为避免时间漂移,把 Task 5 测试里 seedMemory 用的 `lastAccessedAt` 改成 `Instant.now()`。

修改 `LongTermMemoryTest.kt` 的 `seedMemory` 默认参数:

```kotlin
private fun seedMemory(name: String, body: String, lastAccessedAt: Instant = Instant.now()) {
    val content = MemoryFile.serialize(
        MemoryFile.Frontmatter(name, "desc for $name", Instant.parse("2026-01-01T00:00:00Z"), lastAccessedAt),
        body,
    )
    store.writeSync(name, content)
}
```

- [ ] **Step 7: 跑所有长期记忆测试验证通过**

Run: `./gradlew :agent:long-term-memory:test`
Expected: PASS — 所有测试通过

- [ ] **Step 8: 提交**

```bash
git add agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemory.kt \
        agent/long-term-memory/src/main/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryTool.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryTest.kt \
        agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/SaveMemoryToolTest.kt
git commit -m "feat(long-term-memory): 遗忘检查集成到 load/save 路径

- load/save 时基于原始 lastAccessedAt 检查 forgetDuration
- user.md 豁免遗忘
- 触发遗忘时删除文件 + rescan

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: 集成测试 — 端到端 save → load 流程

**Files:**
- Test: `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryIntegrationTest.kt`

**Interfaces:**
- Consumes: 所有前序 task 的产物
- Produces: 端到端验证 save_memory → load_memory 完整流程

- [ ] **Step 1: 写集成测试**

新建 `agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryIntegrationTest.kt`:

```kotlin
package io.github.yeyi.agent.longtermemory

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

class LongTermMemoryIntegrationTest {

    private val tempDir = Files.createTempDirectory("ltm-integration")
    private val store = PathMemoryStore(tempDir)
    private val stubLlm = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            io.github.yeyi.agent.llm.ChatMessage.Assistant("压缩后"),
            null,
            FinishReason.Stop,
        )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { }
    }
    private val registry = MemoryRegistry(store, 8000, Duration.ofDays(30), stubLlm)
    private val ctxFactory = object : CapabilityContextFactory<MemoryContext> {
        override fun create(context: ToolExecutionContext) = MemoryContext(
            memoryStore = store,
            llmProvider = stubLlm,
            registry = registry,
            memoryFileLimit = 8000,
            forgetDuration = Duration.ofDays(30),
        )
    }
    private val saveTool = SaveMemoryTool(registry, ctxFactory, CoroutineScope(SupervisorJob()))
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
        delay(100)

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
        delay(100)
        assertTrue(loadTool.description.contains("food"))
    }

    @Test
    fun `multiple saves append to same topic`() = runTest {
        registry.rescan()
        saveTool.execute(JsonObject(mapOf("name" to JsonPrimitive("hobby"), "content" to JsonPrimitive("摄影"))), execContext())
        saveTool.execute(JsonObject(mapOf("name" to JsonPrimitive("hobby"), "content" to JsonPrimitive("徒步"), "mode" to JsonPrimitive("append"))), execContext())
        delay(100)

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

Run: `./gradlew :agent:long-term-memory:test --tests "io.github.yeyi.agent.longtermemory.LongTermMemoryIntegrationTest"`
Expected: PASS — 3 个集成测试全部通过

- [ ] **Step 3: 提交**

```bash
git add agent/long-term-memory/src/test/kotlin/io/github/yeyi/agent/longtermemory/LongTermMemoryIntegrationTest.kt
git commit -m "test(long-term-memory): 端到端 save→load 集成测试

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 13: 验证全模块测试通过 + 演示接入(可选)

**Files:**
- 无新建文件,跑全量测试验证

- [ ] **Step 1: 跑长期记忆模块全部测试**

Run: `./gradlew :agent:long-term-memory:test`
Expected: PASS — 所有测试通过

- [ ] **Step 2: 跑 capability 模块测试确认无回归**

Run: `./gradlew :agent:capability:test`
Expected: PASS

- [ ] **Step 3: 跑全项目测试确认无回归**

Run: `./gradlew test`
Expected: PASS — 所有模块测试通过

- [ ] **Step 4: (可选)在 demos/agent 加示例**

如果用户希望 demos 里有一个长期记忆示例,可在 `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/runtime/` 下新增 `memories/` 目录,放一个简单的 demo。本步骤可选,根据用户需求决定。

- [ ] **Step 5: 提交(若有 demo 改动)**

```bash
git add demos/agent/
git commit -m "demo(agent): 长期记忆示例接入

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Self-Review

### 1. Spec 覆盖检查

| Spec 章节 | 对应 Task |
|---|---|
| §3 模块结构 | Task 2 |
| §4.1 LongTermMemory | Task 5 |
| §4.2 MemoryContext | Task 6 |
| §4.3 MemoryRegistry + rescan | Task 7 |
| §4.4 MemoryPlugin | Task 9 |
| §4.5 SaveMemoryTool | Task 8(基础)+ Task 10(压缩)+ Task 11(遗忘) |
| §4.6 MemoryStore + PathMemoryStore | Task 4 |
| §4.7 MemoryFile | Task 3 |
| §4.8 MemoryExtensions DSL | Task 9 |
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

- `MemoryFile.Frontmatter` 字段名在所有 Task 一致:`name` / `description` / `createdAt` / `lastAccessedAt`
- `MemoryContext` 构造参数顺序在 Task 6/8/9/10/11 一致:`memoryStore, llmProvider, registry, memoryFileLimit, forgetDuration`
- `MemoryRegistry` 构造参数顺序在所有 Task 一致:`memoryStore, memoryFileLimit, forgetDuration, llmProvider`
- `SaveMemoryTool` 构造参数顺序一致:`registry, contextFactory, compressScope`
- `MemoryPlugin` 构造参数顺序一致:`directory, memoryFileLimit, forgetDuration, llmProvider, memoryStore, enableDelegateAdaptMode, registry`
- `AgentBuilder.longTermMemory` 参数顺序在 Task 9 与 spec §4.8 一致

### 4. 已知偏离 spec

1. **`MemoryPlugin` 不继承 `CapabilityPlugin`** — spec §2/§4.4 已说明原因(`CapabilityPlugin.install` 是 final 且不支持 `appendInstructions`)。Plan Task 9 实现为直接实现 `AgentPlugin<MemoryPluginConfig>`。
2. **`MemoryPluginConfig` object** — `AgentPlugin<C : Any>` 不接受 `Unit`,需要占位 config 类型。spec 未提及,plan 补充。
3. **`DefaultLongTermMemory.load()` 返回提示字符串** — 遗忘检查需要 context,但 `load()` 无参,实际逻辑移到 `activate`。Task 11 Step 4 已说明。
4. **`java.time.Duration` 替代 `kotlin.time.Duration`** — JVM 项目统一用 java.time,与 core 一致。
5. **frontmatter 不引入 YAML 库** — 自写最小化解析,只支持 `key: value` 行 + `---` 分隔符。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-11-25-long-term-memory.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
