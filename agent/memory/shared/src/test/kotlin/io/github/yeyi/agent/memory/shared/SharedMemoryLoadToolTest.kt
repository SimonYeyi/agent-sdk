package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.llm.ContentPart
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
import kotlin.test.assertTrue

class SharedMemoryLoadToolTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private val context: ToolExecutionContext = ToolExecutionContext("call-1", testAgentContext())

    private fun md(
        name: String,
        description: String = "desc",
        body: String = "- $name",
        at: Instant = base,
    ): String = SharedMemory(name, description, at, at).render(body)

    private fun args(name: String?): JsonElement = buildJsonObject {
        if (name != null) put("name", name)
    }

    private fun ToolExecutionResult.text(): String =
        parts.joinToString("") { (it as? ContentPart.Text)?.text ?: "" }

    private fun loaderOf(store: FakeSharedMemoryStore, maxFileCount: Int = 5): SharedMemoryLoader =
        SharedMemoryLoader.create(store, maxFileCount)

    @Test
    fun `description lists every memory in the snapshot`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = loaderOf(store)
        loader.modify("travel") { md("travel", "旅行偏好") }
        loader.modify("food") { md("food", "饮食偏好") }

        val text = SharedMemoryLoadTool(loader).description

        assertTrue(text.contains("- travel：旅行偏好"), text)
        assertTrue(text.contains("- food：饮食偏好"), text)
    }

    @Test
    fun `description is evaluated per read so new memories show up`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = loaderOf(store)
        val tool = SharedMemoryLoadTool(loader)

        assertFalse("- new-one：" in tool.description)

        loader.modify("new-one") { md("new-one", "刚写的") }

        assertTrue("- new-one：刚写的" in tool.description, "description must not be cached")
    }

    @Test
    fun `description shows a placeholder when the snapshot is empty`() = runTest {
        val text = SharedMemoryLoadTool(loaderOf(FakeSharedMemoryStore())).description

        assertTrue("(暂无)" in text)
    }

    @Test
    fun `execute returns the body without frontmatter`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = loaderOf(store)
        loader.modify("travel") { md("travel", body = "- 喜欢海边\n- 不吃辣") }

        val result = SharedMemoryLoadTool(loader).execute(args("travel"), context)

        assertFalse(result.isError)
        assertEquals("- 喜欢海边\n- 不吃辣", result.text())
        assertFalse("---" in result.text())
    }

    @Test
    fun `execute refreshes lastAccessedAt on disk`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = loaderOf(store)
        loader.modify("travel") { md("travel") }

        SharedMemoryLoadTool(loader).execute(args("travel"), context)

        val reread = SharedMemory.parse("travel", store.contentOf("travel")!!)
        assertTrue(reread.lastAccessedAt.isAfter(base))
    }

    @Test
    fun `reserved user name is rejected even when the file exists`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md(SharedMemory.RESERVED_USER, "画像", body = "用户喜欢海鲜"))
        val tool = SharedMemoryLoadTool(loaderOf(store))

        val result = tool.execute(args(SharedMemory.RESERVED_USER), context)

        assertTrue(result.isError)
        assertFalse("用户喜欢海鲜" in result.text(), "must not leak the profile through the tool")
    }

    @Test
    fun `unknown name returns a not found error`() = runTest {
        val result = SharedMemoryLoadTool(loaderOf(FakeSharedMemoryStore())).execute(args("ghost"), context)

        assertTrue(result.isError)
        assertTrue("not found" in result.text())
    }

    @Test
    fun `missing name argument is reported`() = runTest {
        val result = SharedMemoryLoadTool(loaderOf(FakeSharedMemoryStore())).execute(args(null), context)

        assertTrue(result.isError)
        assertTrue("Missing 'name'" in result.text())
    }

    @Test
    fun `memory about to be evicted is still readable`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("a", md("a", body = "- a"))
        store.put("b", md("b", at = base.plusSeconds(1)))
        val loader = loaderOf(store, maxFileCount = 1)

        val result = SharedMemoryLoadTool(loader).execute(args("a"), context)

        assertFalse(result.isError)
        assertEquals("- a", result.text())
        assertTrue(store.exists("a"), "load itself must not evict the memory it reads")
    }
}
