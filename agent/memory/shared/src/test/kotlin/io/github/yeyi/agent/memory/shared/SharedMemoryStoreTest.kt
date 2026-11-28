package io.github.yeyi.agent.memory.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMemoryStoreTest {

    private lateinit var tempDir: Path
    private lateinit var store: PathSharedMemoryStore

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("shared-memory-store-test")
        store = PathSharedMemoryStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun fileOf(name: String): File = File(tempDir.toFile(), "$name.md")

    @Test
    fun `modify creates file and read returns same content`() = runTest {
        val written = store.modify("travel") { "- 喜欢海边" }

        assertEquals("- 喜欢海边", written)
        assertEquals("- 喜欢海边", store.read("travel"))
        assertTrue(fileOf("travel").exists())
    }

    @Test
    fun `modify returning null deletes the file`() = runTest {
        store.modify("travel") { "- 喜欢海边" }

        val result = store.modify("travel") { null }

        assertNull(result)
        assertFalse(store.exists("travel"))
        assertFalse(fileOf("travel").exists())
    }

    @Test
    fun `transform returning current content performs no write`() = runTest {
        store.modify("travel") { "- 喜欢海边" }
        val file = fileOf("travel")
        // 把 mtime 设成纪元 0:只要发生任何写入(含原子写回),mtime 必然被改成当下。
        // 用哨兵值断言"没写",比比较前后 mtime 更确定——不受时间戳精度影响。
        val sentinel = FileTime.fromMillis(0)
        Files.setLastModifiedTime(file.toPath(), sentinel)

        val result = store.modify("travel") { current -> current }

        assertEquals("- 喜欢海边", result)
        assertEquals(sentinel, Files.getLastModifiedTime(file.toPath()))
        assertFalse(File(tempDir.toFile(), "travel.md.tmp").exists())
    }

    @Test
    fun `transform returning null for a missing file creates nothing`() = runTest {
        val result = store.modify("ghost") { current ->
            assertNull(current)
            null
        }

        assertNull(result)
        assertTrue(tempDir.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `list only returns valid memory names`() = runTest {
        store.modify("travel") { "x" }
        store.modify(SharedMemory.RESERVED_USER) { "user body" }
        File(tempDir.toFile(), "中文.md").writeText("x")
        File(tempDir.toFile(), "a b.md").writeText("x")
        File(tempDir.toFile(), "x.md.tmp").writeText("x")

        assertEquals(listOf("travel"), store.list())
    }

    @Test
    fun `invalid name is rejected before touching the filesystem`() = runTest {
        val error = runCatching { store.modify("../escape") { "boom" } }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, got $error")
        assertFalse(File(tempDir.toFile().parentFile, "escape.md").exists())
        assertTrue(tempDir.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `stale tmp file is ignored and can be overwritten`() = runTest {
        File(tempDir.toFile(), "x.md.tmp").writeText("half-written")

        assertFalse("x" in store.list())
        store.modify("x") { "clean" }

        assertEquals("clean", store.read("x"))
        assertFalse(File(tempDir.toFile(), "x.md.tmp").exists())
    }

    @Test
    fun `concurrent same-name writes do not lose updates`() = runTest {
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(20) {
                    launch { store.modify("a") { (it ?: "") + "x" } }
                }
            }
        }

        assertEquals(20, store.read("a")?.length)
    }

    @Test
    fun `content with Chinese and emoji round-trips byte for byte`() = runTest {
        val body = "- 喜欢海鲜与日料 🍣\n- 不吃辣"

        store.modify("food") { body }

        assertEquals(body, store.read("food"))
        assertEquals(body, fileOf("food").readText())
    }
}
