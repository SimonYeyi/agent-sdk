package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.hook.Hook
import io.github.yeyi.agent.hook.HookEvent
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.hook.HookResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class SessionManagerTest {

    private lateinit var tempDir: File

    private fun createMockPipeline(): HookPipeline = HookPipeline()

    /**
     * 构造一个带单个录制 [Hook] 的 [HookPipeline]。
     * [onEvent] 收到所有转发过来的事件,测试侧负责断言。
     */
    private fun recordingPipeline(
        onEvent: (HookEvent) -> Unit,
    ): HookPipeline {
        val hook = object : Hook {
            override val name: String = "recording"
            override suspend fun execute(
                event: HookEvent,
                context: io.github.yeyi.agent.hook.HookContext,
            ): HookResult {
                onEvent(event)
                return HookResult.Continue
            }
        }
        return HookPipeline(listOf(hook))
    }

    @Before
    fun setup() {
        tempDir = createTempDir()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `create should create a new session`() = runTest {
        val sessionManager = SessionManager(tempDir, createMockPipeline())
        val session = sessionManager.create("user1", "test session")

        assertNotNull(session.id)
        assertEquals("user1", session.accountId)
        assertEquals("test session", session.name)
        assertNotNull(session.memory)
        assertEquals(session.createdAt, session.lastActiveAt)
    }

    @Test
    fun `get should return existing session`() = runTest {
        val sessionManager = SessionManager(tempDir, createMockPipeline())
        val created = sessionManager.create("user1", "test session")
        val retrieved = sessionManager.get("user1", created.id)

        assertEquals(created.id, retrieved.id)
        assertEquals(created.accountId, retrieved.accountId)
        assertEquals(created.name, retrieved.name)
        assertNotNull(retrieved.memory)
    }

    @Test
    fun `get should throw when session not found`() = runTest {
        val sessionManager = SessionManager(tempDir, createMockPipeline())
        assertFailsWith<NoSuchElementException> {
            sessionManager.get("user1", "nonexistent")
        }
    }

    @Test
    fun `list should return all sessions for user`() = runTest {
        val sessionManager = SessionManager(tempDir, createMockPipeline())
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
        val sessionManager = SessionManager(tempDir, createMockPipeline())
        val session = sessionManager.create("user1", "test session")
        sessionManager.delete(session)

        assertFailsWith<NoSuchElementException> {
            sessionManager.get("user1", session.id)
        }
        assertTrue(sessionManager.list("user1").isEmpty())
    }

    @Test
    fun `memory should persist messages`() = runTest {
        val sessionManager = SessionManager(tempDir, createMockPipeline())
        val session = sessionManager.create("user1", "test session")
        val memory = session.memory

        memory.add(ChatMessage.User(listOf(ContentPart.Text("Hello"))))
        memory.add(ChatMessage.Assistant(content = "Hi there!"))

        val history = memory.history()
        assertEquals(2, history.size)
        assertEquals("Hello", (history[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
    }

    // --- Session events via HookPipeline ---

    @Test
    fun `hook onSessionCreated is called when creating session`() = runTest {
        val events = mutableListOf<String>()
        val pipeline = recordingPipeline { event ->
            if (event is SessionHookEvent.Created) {
                events.add("onSessionCreated(${event.session.id},${event.session.name})")
            }
        }
        val manager = SessionManager(tempDir, pipeline)
        val session = manager.create("user1", "my session")
        assertEquals(listOf("onSessionCreated(${session.id},my session)"), events)
    }

    @Test
    fun `hook onSessionDeleted is called when deleting session`() = runTest {
        val events = mutableListOf<String>()
        val pipeline = recordingPipeline { event ->
            if (event is SessionHookEvent.Deleted) {
                events.add("onSessionDeleted(${event.session.accountId},${event.session.id})")
            }
        }
        val manager = SessionManager(tempDir, pipeline)
        val session = manager.create("user1", "my session")
        manager.delete(session)
        assertEquals(listOf("onSessionDeleted(user1,${session.id})"), events)
    }

    @Test
    fun `get and list do NOT trigger hooks`() = runTest {
        val events = mutableListOf<String>()
        val pipeline = recordingPipeline { event ->
            if (event is SessionHookEvent.Created || event is SessionHookEvent.Deleted) {
                events.add(event::class.simpleName ?: "")
            }
        }
        val manager = SessionManager(tempDir, pipeline)
        manager.create("user1", "my session")
        events.clear()
        manager.get("user1", manager.list("user1").first().id)
        manager.list("user1")
        assertTrue("get/list should not trigger hooks", events.isEmpty())
    }
}
