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

    // --- SessionHook tests ---

    @Test
    fun `hook onSessionCreated is called when creating session`() = runTest {
        val events = mutableListOf<String>()
        val hook = object : SessionHook {
            override suspend fun onSessionCreated(session: Session) {
                events.add("onSessionCreated(${session.id},${session.name})")
            }
        }
        val manager = SessionManager(tempDir, hook)
        val session = manager.create("user1", "my session")
        assertEquals(listOf("onSessionCreated(${session.id},my session)"), events)
    }

    @Test
    fun `hook onSessionDeleted is called when deleting session`() = runTest {
        val events = mutableListOf<String>()
        val hook = object : SessionHook {
            override suspend fun onSessionDeleted(userId: String, sessionId: String) {
                events.add("onSessionDeleted($userId,$sessionId)")
            }
        }
        val manager = SessionManager(tempDir, hook)
        val session = manager.create("user1", "my session")
        manager.delete("user1", session.id)
        assertEquals(listOf("onSessionDeleted(user1,${session.id})"), events)
    }

    @Test
    fun `exception in hook does not crash create`() = runTest {
        val hook = object : SessionHook {
            override suspend fun onSessionCreated(session: Session) {
                throw RuntimeException("hook fail")
            }
        }
        val manager = SessionManager(tempDir, hook)
        val session = manager.create("user1", "my session")
        assertNotNull(session.id)
    }

    @Test
    fun `exception in hook does not crash delete`() = runTest {
        val hook = object : SessionHook {
            override suspend fun onSessionDeleted(userId: String, sessionId: String) {
                throw RuntimeException("hook fail")
            }
        }
        val manager = SessionManager(tempDir, hook)
        val session = manager.create("user1", "my session")
        manager.delete("user1", session.id)
        assertTrue(manager.list("user1").isEmpty())
    }

    @Test
    fun `get and list do NOT trigger hooks`() = runTest {
        val events = mutableListOf<String>()
        val hook = object : SessionHook {
            override suspend fun onSessionCreated(session: Session) {
                events.add("onSessionCreated")
            }
            override suspend fun onSessionDeleted(userId: String, sessionId: String) {
                events.add("onSessionDeleted")
            }
        }
        val manager = SessionManager(tempDir, hook)
        manager.create("user1", "my session")
        events.clear()
        manager.get("user1", manager.list("user1").first().id)
        manager.list("user1")
        assertTrue("get/list should not trigger hooks", events.isEmpty())
    }
}