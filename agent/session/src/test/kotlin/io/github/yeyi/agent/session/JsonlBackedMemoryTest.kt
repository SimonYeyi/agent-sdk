package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertSame

class JsonlBackedMemoryTest {

    private lateinit var tempDir: File
    private lateinit var memoryFile: File
    private lateinit var archive: MediaArchive
    private lateinit var memory: JsonlBackedMemory

    @Before
    fun setup() {
        tempDir = createTempDir()
        memoryFile = File(tempDir, "test-memory.jsonl")
        archive = object : MediaArchive {
            override fun store(data: MediaSource.Data): MediaSource.Local =
                MediaSource.Local("unused", data.mimeType)
            override fun resolve(local: MediaSource.Local): MediaSource.Data =
                throw UnsupportedOperationException("not used in memory tests")
        }
        memory = JsonlBackedMemory(memoryFile, archive)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `add appends message to file and cache`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("hello"))))
        memory.add(ChatMessage.Assistant(content = "hi"))

        val history = memory.history()
        assertEquals(2, history.size)
        assertEquals("hello", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
        assertEquals("hi", (history[1] as ChatMessage.Assistant).content)
    }

    @Test
    fun `history returns empty list when file does not exist`() = runTest {
        val history = memory.history()
        assertTrue(history.isEmpty())
    }

    @Test
    fun `reload from existing file on next instance`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("first"))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("second"))))

        val reloaded = JsonlBackedMemory(memoryFile, archive)
        val history = reloaded.history()
        assertEquals(2, history.size)
        assertEquals("first", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
        assertEquals("second", (history[1] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
    }

    @Test
    fun `rebuild replaces all messages atomically`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("old1"))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("old2"))))

        val newMessages = listOf(
            ChatMessage.User(listOf(ContentPart.Text("new1"))),
            ChatMessage.Assistant(content = "new2")
        )
        memory.rebuild(newMessages)

        val history = memory.history()
        assertEquals(2, history.size)
        assertEquals("new1", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
        assertEquals("new2", (history[1] as ChatMessage.Assistant).content)
    }

    @Test
    fun `rebuild with empty list clears all messages`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("a"))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("b"))))

        memory.rebuild(emptyList())

        assertTrue(memory.history().isEmpty())
    }

    @Test
    fun `rebuild writes to file correctly`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("original"))))

        memory.rebuild(listOf(ChatMessage.User(listOf(ContentPart.Text("replaced")))))

        val reloaded = JsonlBackedMemory(memoryFile, archive)
        val history = reloaded.history()
        assertEquals(1, history.size)
        assertEquals("replaced", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
    }

    @Test
    fun `supports all ChatMessage types roundtrip`() = runTest {
        val messages = listOf(
            ChatMessage.System("sys"),
            ChatMessage.User(listOf(ContentPart.Text("usr"))),
            ChatMessage.Assistant(content = "asst", toolCalls = emptyList()),
            ChatMessage.ToolResult(toolCallId = "tc1", toolName = "echo", parts = listOf(ContentPart.Text("result"))),
        )

        memory.rebuild(messages)

        val history = memory.history()
        assertEquals(4, history.size)
        assertTrue(history[0] is ChatMessage.System)
        assertTrue(history[1] is ChatMessage.User)
        assertTrue(history[2] is ChatMessage.Assistant)
        assertTrue(history[3] is ChatMessage.ToolResult)
    }

    @Test
    fun `history returns a defensive copy`() = runTest {
        memory.add(ChatMessage.User(listOf(ContentPart.Text("a"))))

        val h1 = memory.history()
        val h2 = memory.history()

        assertNotSame(h1, h2)
        assertEquals(h1.size, h2.size)
    }

    @Test
    fun `add after rebuild works correctly`() = runTest {
        memory.rebuild(listOf(ChatMessage.User(listOf(ContentPart.Text("first")))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("second"))))

        val history = memory.history()
        assertEquals(2, history.size)
        assertEquals("first", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
        assertEquals("second", (history[1] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
    }

    @Test
    fun `mediaArchive field returns injected archive instance`() = runTest {
        assertSame(archive, memory.mediaArchive)
    }
}
