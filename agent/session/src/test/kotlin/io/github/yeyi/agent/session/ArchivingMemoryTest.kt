package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertSame

class ArchivingMemoryTest {

    /**
     * 记录所有 add() 收到的 message,持有 archive —— 用于验证
     * ArchivingMemory 是否真的把 archived 版本传给底层 Memory。
     */
    private class CapturingMemory(override val mediaArchive: MediaArchive) : Memory {
        val added: MutableList<ChatMessage> = mutableListOf()
        override suspend fun add(message: ChatMessage) {
            added.add(message)
        }
        override suspend fun history(): List<ChatMessage> = added.toList()
        override suspend fun rebuild(messages: List<ChatMessage>) {
            added.clear()
            added.addAll(messages)
        }
    }

    /**
     * 每次 store 自增 ID + 计数。resolve 不需要 —— ArchivingMemory 测试不调用。
     */
    private class CountingArchive : MediaArchive {
        var storeCount: Int = 0
            private set
        override suspend fun store(data: MediaSource.Data): MediaSource.Local {
            storeCount++
            return MediaSource.Local(fileId = "id-$storeCount", mimeType = data.mimeType)
        }
        override suspend fun resolve(local: MediaSource.Local): MediaSource.Data {
            throw UnsupportedOperationException("not used in ArchivingMemory tests")
        }
    }

    @Test
    fun `boundary 1024 base64 passes through unchanged (no archive)`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        val base64 = "x".repeat(1024)
        archiving.add(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", base64))))
        )

        assertEquals(0, archive.storeCount)
        assertEquals(1, memory.added.size)
        val stored = memory.added[0] as ChatMessage.User
        val source = (stored.parts[0] as ContentPart.Image).source
        assertTrue("expected Data passthrough, got $source", source is MediaSource.Data)
    }

    @Test
    fun `1025 base64 chars triggers archive to Local`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        val base64 = "x".repeat(1025)
        archiving.add(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", base64))))
        )

        assertEquals(1, archive.storeCount)
        assertEquals(1, memory.added.size)
        val stored = memory.added[0] as ChatMessage.User
        val source = (stored.parts[0] as ContentPart.Image).source
        assertTrue("expected Local after archive, got $source", source is MediaSource.Local)
        val local = source as MediaSource.Local
        assertEquals("id-1", local.fileId)
        assertEquals("image/png", local.mimeType)
    }

    @Test
    fun `empty base64 (length 0) does not trigger archive`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        archiving.add(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", ""))))
        )

        assertEquals(0, archive.storeCount)
        val stored = memory.added[0] as ChatMessage.User
        val source = (stored.parts[0] as ContentPart.Image).source
        assertTrue(source is MediaSource.Data)
    }

    @Test
    fun `ToolResult with large media also archives`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        val base64 = "y".repeat(2000)
        archiving.add(
            ChatMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "fetch",
                parts = listOf(ContentPart.Audio(MediaSource.Data("audio/mp3", base64)))
            )
        )

        assertEquals(1, archive.storeCount)
        val stored = memory.added[0] as ChatMessage.ToolResult
        val source = (stored.parts[0] as ContentPart.Audio).source
        assertTrue("expected Local, got $source", source is MediaSource.Local)
    }

    @Test
    fun `System and Assistant messages pass through without archiving`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        archiving.add(ChatMessage.System("sys-prompt"))
        archiving.add(ChatMessage.Assistant(content = "ok", toolCalls = emptyList()))

        assertEquals(0, archive.storeCount)
        assertEquals(2, memory.added.size)
        assertTrue(memory.added[0] is ChatMessage.System)
        assertTrue(memory.added[1] is ChatMessage.Assistant)
    }

    @Test
    fun `history and rebuild do not trigger archive`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        val preArchived = listOf(
            ChatMessage.User(
                listOf(
                    ContentPart.Image(MediaSource.Local(fileId = "existing", mimeType = "image/png"))
                )
            )
        )
        memory.rebuild(preArchived)

        val h = archiving.history()
        archiving.rebuild(preArchived)

        assertEquals(0, archive.storeCount)
        assertEquals(1, h.size)
        val hSource = (h[0] as ChatMessage.User).parts[0].let { (it as ContentPart.Image).source }
        assertTrue("history returned archived state unchanged", hSource is MediaSource.Local)
    }

    @Test
    fun `mediaArchive is delegated to decorated`() = runTest {
        val archive = CountingArchive()
        val memory = CapturingMemory(archive)
        val archiving = ArchivingMemory(memory)

        assertSame(archive, archiving.mediaArchive)
    }
}
