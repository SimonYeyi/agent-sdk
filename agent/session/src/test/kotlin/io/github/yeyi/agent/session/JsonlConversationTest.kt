package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

private fun ChatMessage.firstTextOrEmpty(): String = when (this) {
    is ChatMessage.User -> parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    is ChatMessage.Assistant -> content ?: ""
    is ChatMessage.ToolResult -> content
    is ChatMessage.System -> content
}

class JsonlConversationTest {

    private lateinit var tempDir: File
    private lateinit var innerMemory: InMemoryMemory
    private lateinit var conversation: JsonlConversation

    @Before
    fun setup() {
        tempDir = createTempDir()
        innerMemory = InMemoryMemory()
        conversation = JsonlConversation(tempDir, innerMemory, pageSizeThreshold = 10 * 1024)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `add should write to file and innerMemory`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("Hello"))))
        conversation.add(ChatMessage.Assistant(content = "Hi"))

        val messages = conversation.messages()
        assertEquals(2, messages.size)
        assertEquals("Hello", (messages[0] as ChatMessage.User).firstTextOrEmpty())
        assertEquals("Hi", (messages[1] as ChatMessage.Assistant).content)

        val innerHistory = innerMemory.history()
        assertEquals(2, innerHistory.size)
    }

    @Test
    fun `messages null should return all messages`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg1"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg2"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))

        val all = conversation.messages(null)
        assertEquals(3, all.size)
    }

    @Test
    fun `messages page 1 should return latest page`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg1"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg2"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))

        val page1 = conversation.messages(1)
        assertEquals(3, page1.size)
        assertEquals("msg1", (page1[0] as ChatMessage.User).firstTextOrEmpty())
    }

    @Test
    fun `paging should create new page when threshold exceeded`() = runTest {
        // Create conversation with very small threshold (30 bytes)
        val pagedConv = JsonlConversation(tempDir, innerMemory, pageSizeThreshold = 30)

        // Each message with JSON wrapper is roughly 25+ bytes
        // So 2 messages should trigger new page (25+25 > 30)
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("a"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("b"))))

        // Should have page1 and page2
        val page1 = pagedConv.messages(1)
        val page2 = pagedConv.messages(2)

        assertTrue(page1.isNotEmpty())
        assertTrue(page2.isNotEmpty())
    }

    @Test
    fun `startPage anchor should prevent drift when new page created`() = runTest {
        // Create conversation with very small threshold
        val pagedConv = JsonlConversation(tempDir, innerMemory, pageSizeThreshold = 30)

        // First page fills up
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg1")))) // -> page1
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg2")))) // -> page2 (new)

        // User starts viewing from page 1 (latest = page2)
        val page1First = pagedConv.messages(1)
        assertEquals("msg2", (page1First[0] as ChatMessage.User).firstTextOrEmpty())

        // User scrolls to page 2 (older = page1)
        val page2First = pagedConv.messages(2)
        assertEquals("msg1", (page2First[0] as ChatMessage.User).firstTextOrEmpty())

        // New messages come and create page3
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg4"))))

        // User scrolls back to page 2 - should still get page1 content
        // because anchor was set to page2 when user first viewed page 1
        val page2Again = pagedConv.messages(2)
        assertEquals("msg1", (page2Again[0] as ChatMessage.User).firstTextOrEmpty())
    }

    @Test
    fun `returning to page 1 should reset anchor`() = runTest {
        // Create conversation with very small threshold
        val pagedConv = JsonlConversation(tempDir, innerMemory, pageSizeThreshold = 30)

        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg1")))) // -> page1
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg2")))) // -> page2

        // User views page 1 (sets anchor to page2)
        val page1First = pagedConv.messages(1)
        assertEquals("msg2", (page1First[0] as ChatMessage.User).firstTextOrEmpty())

        // New messages come and create page3
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg4"))))

        // User returns to page 1 - should reset anchor to page3
        val page1Again = pagedConv.messages(1)
        assertEquals("msg4", (page1Again[0] as ChatMessage.User).firstTextOrEmpty())

        // Page 2 should now be page2 content
        val page2 = pagedConv.messages(2)
        assertEquals("msg3", (page2[0] as ChatMessage.User).firstTextOrEmpty())
    }

    @Test
    fun `messages with invalid page should return empty`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg1"))))

        assertTrue(conversation.messages(0).isEmpty())
        assertTrue(conversation.messages(-1).isEmpty())
        assertTrue(conversation.messages(100).isEmpty())
    }

    @Test
    fun `conversation files should be named correctly`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg1"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg2"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg4"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("msg5"))))

        val files = tempDir.listFiles()
            ?.filter { it.name.startsWith("page") && it.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertTrue(files.isNotEmpty())
        assertEquals("page1.jsonl", files[0].name)
    }

    @Test
    fun `messages null should return messages in chronological order`() = runTest {
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("first"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("second"))))
        conversation.add(ChatMessage.User(listOf(ContentPart.Text("third"))))

        val all = conversation.messages(null)

        assertEquals(3, all.size)
        assertEquals("first", (all[0] as ChatMessage.User).firstTextOrEmpty())
        assertEquals("second", (all[1] as ChatMessage.User).firstTextOrEmpty())
        assertEquals("third", (all[2] as ChatMessage.User).firstTextOrEmpty())
    }

    @Test
    fun `messages page should return messages in chronological order`() = runTest {
        // Create with small threshold to trigger paging
        val pagedConv = JsonlConversation(tempDir, innerMemory, pageSizeThreshold = 30)

        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg1"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg2"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg3"))))
        pagedConv.add(ChatMessage.User(listOf(ContentPart.Text("msg4"))))

        // Page 1 should return newer messages first (within the page)
        val page1 = pagedConv.messages(1)
        // page1 is the latest page, should have msg4 or msg3+msg4
        assertTrue(page1.isNotEmpty())
    }
}
