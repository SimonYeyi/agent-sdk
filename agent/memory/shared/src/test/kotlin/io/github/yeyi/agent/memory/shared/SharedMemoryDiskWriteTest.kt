package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 落盘行为:哪些操作真的碰磁盘。
 *
 * 背景:store 在 transform 返回与当前内容相同时短路,不写 `.tmp`。
 * 上面的 store 测试验证了机制,这里验证动机——稳态 install 与 save 的失败路径
 * 必须零 IO,而 load 的写回必须照旧(它改 lastAccessedAt,内容必然不同)。
 *
 * 断言手法:把文件 mtime 置成纪元 0 当哨兵。只要发生任何写入(含原子写回),
 * mtime 必然被改成当下;比比较前后 mtime 确定,不受时间戳精度影响。
 * 每个"没写"的用例都配一个"确实会写"的对照,避免哨兵本身失效却不自知。
 */
class SharedMemoryDiskWriteTest {

    private val sentinel: FileTime = FileTime.fromMillis(0)

    private val context: ToolExecutionContext = ToolExecutionContext("call-1", testAgentContext())

    private lateinit var directory: Path
    private lateinit var store: PathSharedMemoryStore
    private lateinit var loader: SharedMemoryLoader

    @Before
    fun setup() {
        directory = Files.createTempDirectory("shared-memory-disk-write")
        store = PathSharedMemoryStore(directory)
        loader = SharedMemoryLoader.create(store, 5)   // 绕过注册表,避免用例间共享快照
    }

    @After
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun fileOf(name: String): Path = directory.resolve("$name.md")

    private fun freeze(name: String) {
        Files.setLastModifiedTime(fileOf(name), sentinel)
    }

    private fun assertUntouched(name: String) {
        assertEquals(sentinel, Files.getLastModifiedTime(fileOf(name)), "$name.md 不应被写入")
        assertFalse(Files.exists(directory.resolve("$name.md.tmp")), "$name.md.tmp 不应残留")
    }

    private fun md(name: String, body: String = "- a"): String {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        return SharedMemory(name, "desc", at, at).render(body)
    }

    private fun args(
        name: String? = null,
        description: String? = null,
        content: String? = null,
        mode: String? = null,
        oldText: String? = null,
    ): JsonElement = buildJsonObject {
        if (name != null) put("name", name)
        if (description != null) put("description", description)
        if (content != null) put("content", content)
        if (mode != null) put("mode", mode)
        if (oldText != null) put("old_text", oldText)
    }

    private fun saveTool(maxFileCharSize: Int = 8_000): SharedMemorySaveTool =
        SharedMemorySaveTool(loader, maxFileCharSize, FakeLlmProvider(), null)

    private class RecordingContext : AgentPluginContext {
        val instructions = mutableListOf<Pair<String, String?>>()
        override fun registerTool(tool: Tool) = Unit
        override fun addInstruction(content: String, label: String?) {
            instructions += content to label
        }
    }

    private fun install(loader: SharedMemoryLoader, context: AgentPluginContext) {
        SharedMemoryPlugin(loader, maxFileCharSize = 8_000, llmProvider = FakeLlmProvider()).install(context)
    }

    // ---------- install:稳态零 IO,但仍要注入 user 段落 ----------

    @Test
    fun `a second install does not rewrite user md`() {
        install(loader, RecordingContext())          // 首次:建 user.md
        val body = Files.readString(fileOf(SharedMemory.RESERVED_USER))
        freeze(SharedMemory.RESERVED_USER)

        install(loader, RecordingContext())          // 再次:内容未变,不应落盘

        assertUntouched(SharedMemory.RESERVED_USER)
        assertEquals(body, Files.readString(fileOf(SharedMemory.RESERVED_USER)))
    }

    @Test
    fun `a second install still injects the existing user body`() {
        install(loader, RecordingContext())
        Files.writeString(fileOf(SharedMemory.RESERVED_USER), md(SharedMemory.RESERVED_USER, "- 偏好中文"))
        freeze(SharedMemory.RESERVED_USER)

        // 短路发生在 store 写盘层,不能顺手把注入也跳过:正文照旧要多给 persona 一份
        val context = RecordingContext()
        install(loader, context)

        assertEquals(listOf<Pair<String, String?>>("- 偏好中文" to "user-profile"), context.instructions)
        assertUntouched(SharedMemory.RESERVED_USER)   // 但这一次没有写
    }

    // ---------- load:写回刷新 lastAccessedAt,不能被短路误伤 ----------

    @Test
    fun `load still rewrites the file to refresh lastAccessedAt`() = runTest {
        loader.modify("travel") { md("travel") }
        freeze("travel")

        loader.load("travel")

        assertTrue(
            Files.getLastModifiedTime(fileOf("travel")) != sentinel,
            "load 必须写回刷新 lastAccessedAt,不能被内容相等判定吃掉",
        )
    }

    // ---------- save:失败路径零 IO ----------

    @Test
    fun `save failure paths do not touch the disk`() = runTest {
        loader.modify("travel") { md("travel") }
        freeze("travel")
        val tool = saveTool(maxFileCharSize = 100)   // 硬门 = 400 字符

        val notFound = tool.execute(
            args(name = "travel", mode = "update", oldText = "- z", content = "- Z"), context,
        )
        val missingDescription = tool.execute(args(name = "ghost", content = "- x"), context)
        val tooLarge = tool.execute(args(name = "travel", content = "x".repeat(500)), context)

        assertTrue(notFound.isError, notFound.parts.toString())
        assertTrue("old_text not found" in notFound.parts.toString())
        assertTrue(missingDescription.isError)
        assertTrue(tooLarge.isError)
        assertUntouched("travel")
        assertFalse(Files.exists(fileOf("ghost")), "缺 description 不应建文件")
    }

    // ---------- 对照组:成功路径确实会写,证明上面的哨兵没失效 ----------

    @Test
    fun `a successful save writes and bumps mtime`() = runTest {
        loader.modify("travel") { md("travel") }
        freeze("travel")

        val result = saveTool().execute(args(name = "travel", content = "- b"), context)

        assertFalse(result.isError, result.parts.toString())
        assertTrue(Files.getLastModifiedTime(fileOf("travel")) != sentinel, "成功写入必须刷新 mtime")
        assertEquals("- a\n- b", SharedMemory.bodyOf(Files.readString(fileOf("travel"))))
    }
}
