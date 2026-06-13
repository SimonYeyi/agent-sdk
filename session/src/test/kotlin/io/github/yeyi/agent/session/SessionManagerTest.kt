package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class SessionManagerTest {

    private lateinit var tempDir: File
    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        tempDir = createTempDir()
        sessionManager = SessionManager(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `create should create a new session`() = runTest {
        val session = sessionManager.create("user1", "test session")

        assertNotNull(session.id)
        assertEquals("user1", session.userId)
        assertEquals("test session", session.name)
        assertNotNull(session.memory)
        assertEquals(session.createdAt, session.lastActiveAt)
    }

    @Test
    fun `get should return existing session`() = runTest {
        val created = sessionManager.create("user1", "test session")
        val retrieved = sessionManager.get("user1", created.id)

        assertEquals(created.id, retrieved.id)
        assertEquals(created.userId, retrieved.userId)
        assertEquals(created.name, retrieved.name)
        assertNotNull(retrieved.memory)
    }

    @Test
    fun `get should throw when session not found`() = runTest {
        assertFailsWith<NoSuchElementException> {
            sessionManager.get("user1", "nonexistent")
        }
    }

    @Test
    fun `list should return all sessions for user`() = runTest {
        val session1 = sessionManager.create("user1", "session 1")
        val session2 = sessionManager.create("user1", "session 2")
        val session3 = sessionManager.create("user2", "session 3")

        val user1Sessions = sessionManager.list("user1")
        val user2Sessions = sessionManager.list("user2")

        assertEquals(2, user1Sessions.size)
        assertEquals(1, user2Sessions.size)
        assertTrue(user1Sessions.any { it.id == session1.id })
        assertTrue(user1Sessions.any { it.id == session2.id })
        assertTrue(user2Sessions.any { it.id == session3.id })
    }

    @Test
    fun `delete should remove session`() = runTest {
        val session = sessionManager.create("user1", "test session")
        sessionManager.delete("user1", session.id)

        assertFailsWith<NoSuchElementException> {
            sessionManager.get("user1", session.id)
        }
        assertTrue(sessionManager.list("user1").isEmpty())
    }

    @Test
    fun `memory should persist messages`() = runTest {
        val session = sessionManager.create("user1", "test session")
        val memory = session.memory

        memory.add(ChatMessage.User("Hello"))
        memory.add(ChatMessage.Assistant(content = "Hi there!"))

        val history = memory.history()
        assertEquals(2, history.size)
        assertEquals("Hello", (history[0] as ChatMessage.User).content)
    }

    @Test
    fun `memory clear should remove all messages`() = runTest {
        val session = sessionManager.create("user1", "test session")
        val memory = session.memory

        memory.add(ChatMessage.User("Hello"))
        memory.add(ChatMessage.Assistant(content = "Hi"))
        memory.clear()

        assertTrue(memory.history().isEmpty())
    }
}