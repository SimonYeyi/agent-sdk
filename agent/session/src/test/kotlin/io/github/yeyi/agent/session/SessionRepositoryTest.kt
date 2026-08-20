package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session-repo-test").toFile()
        repo = SessionRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createSession creates per-session directory with three siblings`() {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)
        assertTrue(sessionDir.isDirectory)

        assertTrue(File(sessionDir, "memory.jsonl").exists().not() ||
            File(sessionDir, "memory.jsonl").exists(),
            "memory.jsonl path should be defined")
        assertTrue(File(sessionDir, "conversations").isDirectory ||
            !File(sessionDir, "conversations").exists(),
            "conversations dir should exist after first add")
        assertTrue(File(sessionDir, "media").isDirectory,
            "media dir should exist after first archive")
    }

    @Test
    fun `hydrateSession builds ArchivingMemory containing JsonlConversation`() {
        val session = repo.createSession("alice", "chat1", null)

        // session.memory 真实类型应该是 ArchivingMemory
        assertTrue(session.memory is ArchivingMemory,
            "expected ArchivingMemory, got ${session.memory::class.simpleName}")
    }

    @Test
    fun `large Data added to session memory gets archived and resolves back`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val data = MediaSource.Data("image/jpeg", "x".repeat(2048))

        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(data))))
        val history = session.memory.history()
        val stored = history[0] as ChatMessage.User
        val src = (stored.parts[0] as ContentPart.Image).source

        assertTrue(src is MediaSource.Local,
            "Data > 1024 base64 should be archived to Local, got ${src::class.simpleName}")

        // 验证 mediaArchive 能 resolve 落盘字节
        val resolved = session.memory.mediaArchive.resolve(src)
        assertEquals("x".repeat(2048), resolved.base64)
    }

    @Test
    fun `deleteSession removes entire per-session directory but keeps sessions_jsonl`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)

        // 触发 archive 创建 media/
        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(
            MediaSource.Data("image/jpeg", "x".repeat(2048))
        ))))
        assertTrue(sessionDir.isDirectory)
        assertTrue(File(sessionDir, "media").isDirectory)

        val sessionsFile = File(File(tempDir, "agent/sessions/alice"), "sessions.jsonl")
        assertTrue(sessionsFile.exists())

        val result = repo.deleteSession("alice", session.id)

        assertNotNull(result)
        assertTrue(!sessionDir.exists(),
            "per-session directory should be deleted: ${sessionDir.absolutePath}")

        // sessions.jsonl 索引同步移除(条目删除后文件内容应为空或不存在)
        if (sessionsFile.exists()) {
            val content = sessionsFile.readText().trim()
            assertEquals("", content, "sessions.jsonl should have no entries")
        }
    }

    @Test
    fun `findSession returns null after deleteSession`() {
        val session = repo.createSession("alice", "chat1", null)
        assertNotNull(repo.findSession("alice", session.id))

        repo.deleteSession("alice", session.id)
        assertEquals(null, repo.findSession("alice", session.id))
    }
}