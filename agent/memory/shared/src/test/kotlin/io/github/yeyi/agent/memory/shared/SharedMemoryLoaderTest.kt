package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedMemoryLoaderTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun at(seconds: Long): Instant = base.plusSeconds(seconds)

    private fun content(name: String, accessedAt: Instant, body: String = "- $name"): String =
        SharedMemory(name, "", accessedAt, accessedAt).render(body)

    @Test
    fun `modify makes the new memory visible in the snapshot`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        loader.modify("travel") { content("travel", at(1), "- 喜欢海边") }

        assertEquals(listOf("travel"), loader.all().map { it.name })
    }

    @Test
    fun `load refreshes lastAccessedAt and returns body`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("travel", content("travel", at(0), "- 喜欢海边"))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        val body = loader.load("travel")

        assertEquals("- 喜欢海边", body)
        val reread = SharedMemory.parse("travel", store.contentOf("travel")!!)
        assertTrue(reread.lastAccessedAt.isAfter(at(0)))
    }

    @Test
    fun `load of a missing name returns null and creates nothing`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        assertNull(loader.load("ghost"))
        assertFalse(store.exists("ghost"))
    }

    @Test
    fun `load does not evict the memory it just read even when over limit`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("a", content("a", at(0)))
        store.put("b", content("b", at(1)))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 1)

        assertEquals("- a", loader.load("a"))

        assertTrue(store.exists("a"), "the touched memory must survive its own reload")
        assertFalse(store.exists("b"), "the other, colder memory is the eviction candidate")
    }

    @Test
    fun `no eviction while the count stays within the limit`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        loader.modify("a") { content("a", at(0)) }
        loader.modify("b") { content("b", at(1)) }
        loader.modify("c") { content("c", at(2)) }

        assertEquals(setOf("a", "b", "c"), loader.all().map { it.name }.toSet())
        assertTrue(store.list().containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `snapshot is ordered by name`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("c", content("c", at(2)))
        store.put("a", content("a", at(0)))
        store.put("b", content("b", at(1)))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        loader.load("a")

        // 清单顺序不能随文件系统枚举顺序变:load_shared_memory 的工具描述逐轮稳定
        assertEquals(listOf("a", "b", "c"), loader.all().map { it.name })
    }

    @Test
    fun `evicts the least recently accessed when over limit`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, maxFileCount = 2)

        loader.modify("a") { content("a", at(0)) }
        loader.modify("b") { content("b", at(1)) }
        loader.modify("c") { content("c", at(2)) }

        assertFalse(store.exists("a"))
        assertEquals(setOf("b", "c"), loader.all().map { it.name }.toSet())
    }

    @Test
    fun `tie on lastAccessedAt is broken by name ascending`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("a", content("a", at(0)))
        store.put("b", content("b", at(0)))
        store.put("c", content("c", at(9)))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 2)

        loader.load("c")

        assertFalse(store.exists("a"), "among ties the lexicographically smaller name goes first")
        assertTrue(store.exists("b"))
    }

    @Test
    fun `evicts down to the limit in a single pass`() = runTest {
        val store = FakeSharedMemoryStore()
        listOf("a", "b", "c", "d", "e").forEachIndexed { i, name ->
            store.put(name, content(name, at(i.toLong())))
        }
        val loader = SharedMemoryLoader.create(store, maxFileCount = 2)

        loader.load("e")

        assertEquals(setOf("d", "e"), loader.all().map { it.name }.toSet())
        assertFalse(store.exists("a"))
        assertFalse(store.exists("b"))
        assertFalse(store.exists("c"))
    }

    @Test
    fun `never evicts below one memory`() = runTest {
        val store = FakeSharedMemoryStore()
        val loader = SharedMemoryLoader.create(store, maxFileCount = 1)

        loader.modify("a") { content("a", at(0)) }
        loader.modify("b") { content("b", at(1)) }
        loader.modify("c") { content("c", at(2)) }

        assertEquals(1, loader.all().size)
        assertEquals("c", loader.all().single().name)
    }

    @Test
    fun `candidate touched during eviction is skipped`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("a", content("a", at(0)))
        store.put("b", content("b", at(1)))
        store.put("c", content("c", at(2)))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 2)

        // reload 在淘汰 a 前会调用 store.modify("a"):此刻把 a 的时间戳改成最新,
        // 相当于"另一个协程刚好 load 了 a",二次判定必须放过它
        store.onBeforeModify = { name ->
            if (name == "a") store.put("a", content("a", at(100)))
        }

        loader.load("c")

        assertTrue(store.exists("a"), "concurrently touched candidate must be skipped")
        assertFalse(store.exists("b"), "the next coldest one is evicted instead")
        assertEquals(setOf("a", "c"), loader.all().map { it.name }.toSet())
    }

    @Test
    fun `unreadable file is skipped without being deleted`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put("good", content("good", at(0)))
        store.put("bad", content("bad", at(1)))
        store.failOnRead = setOf("bad")
        val loader = SharedMemoryLoader.create(store, maxFileCount = 5)

        assertEquals("- good", loader.load("good"))

        assertEquals(listOf("good"), loader.all().map { it.name })
        assertTrue(store.exists("bad"), "a broken file must not be deleted by reload")
    }

    @Test
    fun `create is independent of the registry`() = runTest {
        val first = SharedMemoryLoader.create(FakeSharedMemoryStore(), maxFileCount = 3)
        val second = SharedMemoryLoader.create(FakeSharedMemoryStore(), maxFileCount = 3)

        assertNotSame(first, second)
    }

    // ---- of(): 按目录单例 ----

    private lateinit var tempDir: Path

    @Before
    fun setupDir() {
        tempDir = Files.createTempDirectory("shared-memory-loader-test")
    }

    @After
    fun tearDownDir() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `of returns the same instance for the same directory`() {
        val first = SharedMemoryLoader.of(tempDir, maxFileCount = 3)
        val second = SharedMemoryLoader.of(tempDir, maxFileCount = 3)

        assertSame(first, second)
    }

    @Test
    fun `of normalizes different spellings of the same path`() {
        val direct = SharedMemoryLoader.of(tempDir, maxFileCount = 3)
        val roundabout = SharedMemoryLoader.of(tempDir.resolve("..").resolve(tempDir.fileName), maxFileCount = 3)

        assertSame(direct, roundabout)
    }

    @Test
    fun `of rejects a conflicting maxFileCount`() {
        SharedMemoryLoader.of(tempDir, maxFileCount = 3)

        val error = runCatching { SharedMemoryLoader.of(tempDir, maxFileCount = 7) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, got $error")
    }

    @Test
    fun `user does not occupy a slot and is never evicted`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, content(SharedMemory.RESERVED_USER, at(0), "画像"))
        store.put("a", content("a", at(1)))
        store.put("b", content("b", at(2)))
        val loader = SharedMemoryLoader.create(store, maxFileCount = 2)

        loader.load("a")

        assertTrue(store.exists(SharedMemory.RESERVED_USER))
        assertEquals(setOf("a", "b"), loader.all().map { it.name }.toSet())
    }
}
