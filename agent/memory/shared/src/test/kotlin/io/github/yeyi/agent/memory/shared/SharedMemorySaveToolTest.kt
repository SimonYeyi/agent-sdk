package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMemorySaveToolTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private val context: ToolExecutionContext = ToolExecutionContext("call-1", testAgentContext())

    private fun md(
        name: String,
        description: String = "desc",
        body: String = "- $name",
        at: Instant = base,
    ): String = SharedMemory(name, description, at, at).render(body)

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

    private fun ToolExecutionResult.text(): String =
        parts.joinToString("") { (it as? ContentPart.Text)?.text ?: "" }

    private fun providerReturning(response: String): FakeLlmProvider = FakeLlmProvider(
        nonStreamResponses = listOf(
            ChatResponse(
                message = ChatMessage.Assistant(content = response),
                finishReason = FinishReason.Stop,
            )
        )
    )

    private fun tool(
        loader: SharedMemoryLoader,
        maxFileCharSize: Int = 8_000,
        provider: LlmProvider? = FakeLlmProvider(),
        onUserProfileUpdated: ((String) -> Unit)? = null,
    ): SharedMemorySaveTool =
        SharedMemorySaveTool(loader, maxFileCharSize, provider, onUserProfileUpdated)

    // ---------- 新建 / 追加 ----------

    @Test
    fun `creating a memory writes full frontmatter and the body`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        val result = tool(loader).execute(
            args(name = "travel", description = "旅行偏好", content = "- 喜欢海边"),
            context,
        )

        assertFalse(result.isError, result.text())
        val raw = store.contentOf("travel")!!
        val meta = SharedMemory.parse("travel", raw)
        assertEquals("travel", meta.name)
        assertEquals("旅行偏好", meta.description)
        assertTrue(meta.createdAt.isAfter(base.minusSeconds(1)))   // 不是 EPOCH 兜底
        assertTrue("- 喜欢海边" in raw)
        assertEquals("- 喜欢海边", SharedMemory.bodyOf(raw))
    }

    @Test
    fun `creating a memory without description is rejected and nothing is written`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        val result = tool(loader).execute(args(name = "travel", content = "- x"), context)

        assertTrue(result.isError)
        assertTrue("Missing 'description'" in result.text(), result.text())
        assertNull(store.contentOf("travel"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `appending to an existing memory keeps description and createdAt`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", "旅行偏好", body = "- 喜欢海边") }

        val result = tool(loader).execute(args(name = "travel", content = "- 喜欢日料"), context)

        assertFalse(result.isError, result.text())
        val raw = store.contentOf("travel")!!
        assertEquals("- 喜欢海边\n- 喜欢日料", SharedMemory.bodyOf(raw))
        val meta = SharedMemory.parse("travel", raw)
        assertEquals("旅行偏好", meta.description)
        assertEquals(base, meta.createdAt)
    }

    @Test
    fun `description passed for an existing memory is ignored`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", "旅行偏好") }

        tool(loader).execute(
            args(name = "travel", description = "别的描述", content = "- 新条目"),
            context,
        )

        val raw = store.contentOf("travel")!!
        assertEquals("旅行偏好", SharedMemory.parse("travel", raw).description)
        assertFalse("别的描述" in raw)
    }

    // ---------- update ----------

    @Test
    fun `update replaces exactly one whole line and keeps the rest in order`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", body = "- a\n- b\n- c") }

        val result = tool(loader).execute(
            args(name = "travel", mode = "update", oldText = "- b", content = "- B"),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals("- a\n- B\n- c", SharedMemory.bodyOf(store.contentOf("travel")!!))
    }

    @Test
    fun `update with empty content deletes the line without leaving a blank one`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", body = "- a\n- b\n- c") }

        val result = tool(loader).execute(
            args(name = "travel", mode = "update", oldText = "- b", content = ""),
            context,
        )

        assertFalse(result.isError, result.text())
        val raw = store.contentOf("travel")!!
        assertEquals("- a\n- c", SharedMemory.bodyOf(raw))
        assertEquals(2, SharedMemory.bodyOf(raw).lines().size)
        assertFalse("\n\n" in raw, "deleting a line must not leave an empty line")
    }

    @Test
    fun `update that matches nothing reports not found and leaves the file untouched`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", body = "- a\n- b") }
        val before = store.contentOf("travel")

        val result = tool(loader).execute(
            args(name = "travel", mode = "update", oldText = "- z", content = "- Z"),
            context,
        )

        assertTrue(result.isError)
        assertTrue("old_text not found" in result.text(), result.text())
        assertEquals(before, store.contentOf("travel"))
    }

    @Test
    fun `update on a missing memory never creates a file`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        // 带 description:建 meta 后进入 update 分支 → 正文为空,任何 old_text 都匹配不上
        val withDescription = tool(loader).execute(
            args(name = "ghost", description = "d", mode = "update", oldText = "- a", content = "- A"),
            context,
        )
        // 不带 description:meta 段先拦下(缺描述优先于 old_text 未命中)
        val withoutDescription = tool(loader).execute(
            args(name = "ghost", mode = "update", oldText = "- a", content = "- A"),
            context,
        )

        assertTrue(withDescription.isError)
        assertTrue("old_text not found" in withDescription.text(), withDescription.text())
        assertTrue(withoutDescription.isError)
        assertTrue("Missing 'description'" in withoutDescription.text(), withoutDescription.text())
        assertNull(store.contentOf("ghost"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `update with missing or blank old_text is rejected before touching the store`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", body = "- a") }
        val before = store.contentOf("travel")
        var storeEntered = false
        store.onBeforeModify = { storeEntered = true }

        val missing = tool(loader).execute(args(name = "travel", mode = "update", content = "- A"), context)
        val blank = tool(loader).execute(
            args(name = "travel", mode = "update", oldText = "   ", content = "- A"),
            context,
        )

        assertTrue(missing.isError)
        assertTrue(blank.isError)
        assertTrue("old_text" in missing.text())
        assertFalse(storeEntered, "argument validation must happen before opening the lock")
        assertEquals(before, store.contentOf("travel"))
    }

    @Test
    fun `quoting half of a line does not match it`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("food") { md("food", body = "- 喜欢海边和日料") }
        val before = store.contentOf("food")

        val result = tool(loader).execute(
            args(name = "food", mode = "update", oldText = "喜欢海边", content = "- 只喜欢日料"),
            context,
        )

        assertTrue(result.isError)
        assertTrue("old_text not found" in result.text(), result.text())
        assertEquals(before, store.contentOf("food"))
    }

    @Test
    fun `duplicate lines only update the first occurrence`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("dup") { md("dup", body = "- a\n- a") }

        val result = tool(loader).execute(
            args(name = "dup", mode = "update", oldText = "- a", content = "- A"),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals("- A\n- a", SharedMemory.bodyOf(store.contentOf("dup")!!))
    }

    @Test
    fun `special characters in old_text are compared literally`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val tricky = "- 价格: \$5 (USD) .*\\d"
        loader.modify("price") { md("price", body = "$tricky\n- 价格: \$6") }

        val result = tool(loader).execute(
            args(name = "price", mode = "update", oldText = tricky, content = "- 已确认"),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals(
            "- 已确认\n- 价格: \$6",
            SharedMemory.bodyOf(store.contentOf("price")!!),
        )
    }

    // ---------- 参数校验 ----------

    @Test
    fun `illegal names are rejected without touching the store`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        var storeEntered = false
        store.onBeforeModify = { storeEntered = true }

        val candidates = listOf("../x", "a/b", "a".repeat(65), "")

        for (candidate in candidates) {
            val result = tool(loader).execute(
                args(name = candidate, description = "d", content = "- x"),
                context,
            )
            assertTrue(result.isError, "name '$candidate' must be rejected")
            assertTrue("Invalid 'name'" in result.text(), result.text())
        }
        assertFalse(storeEntered)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `an unknown mode is rejected and nothing is written`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel", body = "- a") }
        val before = store.contentOf("travel")

        val result = tool(loader).execute(
            args(name = "travel", mode = "insert", content = "- x"),
            context,
        )

        assertTrue(result.isError)
        assertTrue("Invalid mode: insert" in result.text(), result.text())
        assertEquals(before, store.contentOf("travel"))
    }

    // ---------- 时间 / 快照 ----------

    @Test
    fun `saving refreshes lastAccessedAt`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        loader.modify("travel") { md("travel") }

        tool(loader).execute(args(name = "travel", content = "- 新条目"), context)

        val meta = SharedMemory.parse("travel", store.contentOf("travel")!!)
        assertTrue(meta.lastAccessedAt.isAfter(base))
        assertEquals(base, meta.createdAt)
    }

    @Test
    fun `a new memory is visible in the snapshot right after the call`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        assertNull(loader.get("travel"))

        tool(loader).execute(args(name = "travel", description = "旅行偏好", content = "- x"), context)

        assertEquals("旅行偏好", loader.get("travel")?.description)
    }

    // ---------- 压缩 ----------

    @Test
    fun `content over the soft limit is compressed and the summary is stored`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val content = "y".repeat(200)
        val summary = "z".repeat(50)

        val result = tool(loader, maxFileCharSize = 100, provider = providerReturning(summary))
            .execute(args(name = "big", description = "d", content = content), context)

        assertFalse(result.isError, result.text())
        assertEquals(summary, SharedMemory.bodyOf(store.contentOf("big")!!))
        assertTrue("compressed" in result.text(), result.text())
    }

    @Test
    fun `a summary that is not shorter is discarded and the original is stored`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val content = "y".repeat(200)
        val longer = "z".repeat(250)

        val result = tool(loader, maxFileCharSize = 100, provider = providerReturning(longer))
            .execute(args(name = "big", description = "d", content = content), context)

        assertFalse(result.isError, result.text())
        assertEquals(content, SharedMemory.bodyOf(store.contentOf("big")!!))
        assertTrue("WARNING" in result.text(), result.text())
    }

    @Test
    fun `a failing provider keeps the original content and does not throw`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val content = "y".repeat(200)

        // 未脚本化任何响应 → FakeLlmProvider.chat 内部 check 失败并抛异常
        val result = tool(loader, maxFileCharSize = 100, provider = FakeLlmProvider())
            .execute(args(name = "big", description = "d", content = content), context)

        assertFalse(result.isError, result.text())
        assertEquals(content, SharedMemory.bodyOf(store.contentOf("big")!!))
        assertTrue("WARNING" in result.text(), result.text())
    }

    @Test
    fun `content beyond four times the soft limit is rejected and nothing is written`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        val result = tool(loader, maxFileCharSize = 100)
            .execute(args(name = "big", description = "d", content = "y".repeat(401)), context)

        assertTrue(result.isError)
        assertTrue("too large to compress" in result.text(), result.text())
        assertNull(store.contentOf("big"))
    }

    @Test
    fun `content exactly at four times the soft limit is accepted and compressed`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val summary = "z".repeat(50)

        val result = tool(loader, maxFileCharSize = 100, provider = providerReturning(summary))
            .execute(args(name = "big", description = "d", content = "y".repeat(400)), context)

        assertFalse(result.isError, result.text())
        assertEquals(summary, SharedMemory.bodyOf(store.contentOf("big")!!))
    }

    @Test
    fun `the hard limit is measured on the merged body not on the new content`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        // 已有 380 字正文 + 一个换行 + 30 字新条目 = 411 > 4 × 100
        loader.modify("big") { md("big", body = "- " + "x".repeat(378)) }
        val before = store.contentOf("big")

        val result = tool(loader, maxFileCharSize = 100)
            .execute(args(name = "big", content = "n".repeat(30)), context)

        assertTrue(result.isError)
        assertTrue("too large to compress" in result.text(), result.text())
        assertEquals(before, store.contentOf("big"))
    }

    @Test
    fun `the receipt carries no internal size numbers`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        val result = tool(loader).execute(
            args(name = "travel", description = "d", content = "- x"),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals("Saved memory 'travel'.", result.text())
        assertFalse("chars" in result.text())
        assertFalse("length" in result.text())
    }

    // ---------- 与 loader 的联动 ----------

    @Test
    fun `saving past the count limit evicts the oldest memory`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 2)

        tool(loader).execute(args(name = "a", description = "d", content = "- a"), context)
        tool(loader).execute(args(name = "b", description = "d", content = "- b"), context)
        tool(loader).execute(args(name = "c", description = "d", content = "- c"), context)

        assertFalse(store.exists("a"), "the oldest memory must be evicted by the reload after save")
        assertTrue(store.exists("b"))
        assertTrue(store.exists("c"))
        assertNull(loader.get("a"))
        assertEquals(setOf("b", "c"), loader.all().map { it.name }.toSet())
    }

    // ---------- user 画像回调 ----------

    @Test
    fun `the user profile callback receives the stored body`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val received = mutableListOf<String>()

        val result = tool(loader, onUserProfileUpdated = { received += it })
            .execute(
                args(name = SharedMemory.RESERVED_USER, description = "画像", content = "- 喜欢海鲜"),
                context,
            )

        assertFalse(result.isError, result.text())
        assertEquals(listOf("- 喜欢海鲜"), received)
        assertEquals("- 喜欢海鲜", SharedMemory.bodyOf(store.contentOf(SharedMemory.RESERVED_USER)!!))
    }

    @Test
    fun `the profile callback is not invoked on any failure path`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val received = mutableListOf<String>()

        // 缺 description 且文件不存在
        val missingDescription = tool(loader, onUserProfileUpdated = { received += it })
            .execute(args(name = SharedMemory.RESERVED_USER, content = "- x"), context)
        assertTrue(missingDescription.isError)

        // 让 user.md 存在,后两条才走得到体积判断与 old_text 匹配
        store.put(SharedMemory.RESERVED_USER, md(SharedMemory.RESERVED_USER, body = "- 喜欢海鲜"))

        // 超硬门
        val tooLarge = tool(loader, maxFileCharSize = 100, onUserProfileUpdated = { received += it })
            .execute(
                args(name = SharedMemory.RESERVED_USER, content = "y".repeat(401)),
                context,
            )
        assertTrue(tooLarge.isError)

        // update 未命中
        val notFound = tool(loader, onUserProfileUpdated = { received += it })
            .execute(
                args(
                    name = SharedMemory.RESERVED_USER,
                    mode = "update",
                    oldText = "- 不存在",
                    content = "- z",
                ),
                context,
            )
        assertTrue(notFound.isError)

        assertTrue(received.isEmpty(), "persona must not be refreshed by a failed write: $received")
    }

    @Test
    fun `the profile callback is not invoked for other memories`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val received = mutableListOf<String>()

        tool(loader, onUserProfileUpdated = { received += it })
            .execute(args(name = "travel", description = "d", content = "- x"), context)

        assertTrue(received.isEmpty())
    }

    @Test
    fun `a null profile callback changes nothing`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)

        val result = tool(loader, onUserProfileUpdated = null)
            .execute(
                args(name = SharedMemory.RESERVED_USER, description = "画像", content = "- 喜欢海鲜"),
                context,
            )

        assertFalse(result.isError, result.text())
        assertTrue(store.exists(SharedMemory.RESERVED_USER))
    }

    @Test
    fun `the profile callback receives the compressed body not the raw input`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, 5)
        val summary = "z".repeat(50)
        val received = mutableListOf<String>()

        val result = tool(
            loader,
            maxFileCharSize = 100,
            provider = providerReturning(summary),
            onUserProfileUpdated = { received += it },
        ).execute(
            args(name = SharedMemory.RESERVED_USER, description = "画像", content = "y".repeat(200)),
            context,
        )

        assertFalse(result.isError, result.text())
        assertEquals(listOf(summary), received)
    }
}
