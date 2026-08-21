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

        // createSession 仅做 mkdirs,不触发 archive 或 write:
        // memory.jsonl / conversations 都还不存在
        assertTrue(!File(sessionDir, "memory.jsonl").exists(),
            "memory.jsonl should not exist before any add()")
        assertTrue(!File(sessionDir, "conversations").exists(),
            "conversations dir should not exist before any add()")
        // media root 由 FilesystemMediaArchive.store() 首次 archive 时 mkdirs,
        // createSession 后尚未触发 store, 所以 dir 还不存在
        assertTrue(!File(sessionDir, "media").exists(),
            "media dir should not exist before first archive.store()")
    }

    @Test
    fun `first add populates memory conversations and media siblings`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)

        // 模拟 caller: 先 archive 大 Data 落盘, 再把 Local 加进 memory
        // (旧 ArchivingMemory 装饰器已删, 归档现在由 caller 的 ModalityAdapter 完成)
        val data = MediaSource.Data("image/jpeg", "x".repeat(2048))
        val local = session.memory.mediaArchive.store(data)
        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(local))))

        // memory.jsonl 由 JsonlBackedMemory.add 写入
        assertTrue(File(sessionDir, "memory.jsonl").exists(),
            "memory.jsonl should exist after add")
        // conversations/page1.jsonl 由 JsonlConversation.ensureInitialized 创建
        assertTrue(File(File(sessionDir, "conversations"), "page1.jsonl").isFile,
            "conversations/page1.jsonl should exist after first add")
        // media/{uuid} 由 FilesystemMediaArchive.store 写入
        val mediaFiles = File(sessionDir, "media").listFiles()
            ?.filter { it.isFile } ?: emptyList()
        assertTrue(mediaFiles.size == 1,
            "media dir should contain exactly one archived file, got ${mediaFiles.size}")
    }

    @Test
    fun `hydrateSession builds JsonlConversation as the top-level memory`() {
        val session = repo.createSession("alice", "chat1", null)

        // session.memory 真实类型应该是 JsonlConversation (归档由 caller 的
        // ModalityAdapter 在 memory.add() 前完成, 不再走外层 ArchivingMemory 装饰器)
        assertTrue(session.memory is JsonlConversation,
            "expected JsonlConversation, got ${session.memory::class.simpleName}")
    }

    @Test
    fun `large Data archived by caller then added resolves back through mediaArchive`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val data = MediaSource.Data("image/jpeg", "x".repeat(2048))

        // 模拟 caller: 先 archive, 再 memory.add(Local)
        val local = session.memory.mediaArchive.store(data)
        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(local))))

        val history = session.memory.history()
        val stored = history[0] as ChatMessage.User
        val src = (stored.parts[0] as ContentPart.Image).source

        assertTrue(src is MediaSource.Local,
            "stored source should be Local, got ${src::class.simpleName}")

        // 验证 mediaArchive 能 resolve 落盘字节
        val resolved = session.memory.mediaArchive.resolve(src)
        assertEquals("x".repeat(2048), resolved.base64)
    }

    @Test
    fun `deleteSession removes entire per-session directory but keeps sessions_jsonl`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)

        // 触发 archive 创建 media/
        val local = session.memory.mediaArchive.store(
            MediaSource.Data("image/jpeg", "x".repeat(2048))
        )
        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(local))))
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