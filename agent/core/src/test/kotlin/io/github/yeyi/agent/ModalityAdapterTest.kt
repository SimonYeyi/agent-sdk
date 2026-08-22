package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.modality.ToolResultModalityAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 三组测试, 分别覆盖:
 * - [ModalityAdapter.archive]: write 边 Data → Local(阈值规则)
 * - [DefaultModalityAdapter.resolve]: read 边 Local → Data(只对末条 User)
 * - [ToolResultModalityAdapter.adapt]: 末条 ToolResult 拆 text + 合成 User
 */
class ModalityAdapterTest {

    private val dataPart = ContentPart.Image(MediaSource.Data("image/jpeg", "BASE64"))
    private val httpPart = ContentPart.Image(MediaSource.Http("https://example.com/x.png"))
    private val fileIdPart = ContentPart.Image(MediaSource.FileId("file-abc"))
    private val localPart = ContentPart.Image(
        MediaSource.Local(fileId = "550e8400-e29b-41d4-a716-446655440000", mimeType = "image/jpeg"),
    )

    /** 记录 store/resolve 次数, resolve 时按 fileId 找 base64 */
    private class SpyArchive(
        private val backing: MutableMap<String, String> = mutableMapOf(),
        var resolveCount: Int = 0,
        var storeCount: Int = 0,
    ) : MediaArchive {
        override suspend fun store(data: MediaSource.Data): MediaSource.Local {
            storeCount++
            val id = "stored-$storeCount"
            backing[id] = data.base64
            return MediaSource.Local(id, data.mimeType)
        }
        override suspend fun resolve(local: MediaSource.Local): MediaSource.Data {
            resolveCount++
            return MediaSource.Data(
                mimeType = local.mimeType,
                base64 = backing[local.fileId] ?: "RESOLVED-${local.fileId}",
            )
        }
    }

    // ───────── archive() — write 边 Data → Local ─────────

    @Test
    fun `archive User with small Data passes through unchanged`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)

        val out = adapter.archive(ChatMessage.User(listOf(dataPart)))

        assertEquals(0, archive.storeCount)
        assertEquals(dataPart, (out as ChatMessage.User).parts.single())
    }

    @Test
    fun `archive User with large Data converts to Local and calls store once`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val large = ContentPart.Image(MediaSource.Data("image/png", "x".repeat(2048)))

        val out = adapter.archive(ChatMessage.User(listOf(large))) as ChatMessage.User

        assertEquals(1, archive.storeCount)
        val src = (out.parts.single() as ContentPart.Image).source
        assertTrue(src is MediaSource.Local, "expected Local, got $src")
        assertEquals("image/png", (src as MediaSource.Local).mimeType)
    }

    @Test
    fun `archive ToolResult with large media also archives`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val large = ContentPart.Audio(MediaSource.Data("audio/mp3", "y".repeat(2048)))

        val out = adapter.archive(
            ChatMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "fetch",
                parts = listOf(large),
            )
        ) as ChatMessage.ToolResult

        assertEquals(1, archive.storeCount)
        val src = (out.parts.single() as ContentPart.Audio).source
        assertTrue(src is MediaSource.Local, "expected Local, got $src")
    }

    @Test
    fun `archive User with Local Http FileId passes through unchanged`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)

        for (part in listOf(localPart, httpPart, fileIdPart)) {
            val out = adapter.archive(ChatMessage.User(listOf(part))) as ChatMessage.User
            assertEquals(part, out.parts.single())
        }
        assertEquals(0, archive.storeCount)
    }

    @Test
    fun `archive System and Assistant messages pass through unchanged`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)

        val systemOut = adapter.archive(ChatMessage.System("sys"))
        val assistantOut = adapter.archive(ChatMessage.Assistant(content = "ok", toolCalls = emptyList()))

        assertEquals(ChatMessage.System("sys"), systemOut)
        assertEquals("ok", (assistantOut as ChatMessage.Assistant).content)
        assertEquals(0, archive.storeCount)
    }

    @Test
    fun `archive boundary 1024 base64 does not trigger store`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val boundary = ContentPart.Image(MediaSource.Data("image/png", "x".repeat(1024)))

        adapter.archive(ChatMessage.User(listOf(boundary)))

        assertEquals(0, archive.storeCount)
    }

    // ───────── resolve() — read 边 Local → Data(只对末条 User) ─────────

    @Test
    fun `resolve last User with Local resolves to Text ref plus Data`() = runTest {
        val archive = SpyArchive(backing = mutableMapOf("550e8400..." to "BASE64BYTES"))
        val adapter = DefaultModalityAdapter(archive)
        val local = MediaSource.Local("550e8400...", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        val out = adapter.resolve(listOf(lastUser))

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(2, user.parts.size)
        val ref = user.parts[0] as ContentPart.Text
        assertEquals("[local] fileId=550e8400...", ref.text)
        val data = (user.parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals("BASE64BYTES", data.base64)
        assertEquals(1, archive.resolveCount)
    }

    @Test
    fun `resolve last User with Data Http FileId passes through unchanged`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val lastUser = ChatMessage.User(listOf(dataPart, httpPart, fileIdPart))

        val out = adapter.resolve(listOf(lastUser))

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(listOf(dataPart, httpPart, fileIdPart), user.parts)
        assertEquals(0, archive.resolveCount)
    }

    @Test
    fun `resolve cross-round User with Local becomes placeholder without archive resolve`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val history = ChatMessage.User(listOf(localPart))    // 跨 round (i < lastUserIdx)
        val current = ChatMessage.User(listOf(ContentPart.Text("next question")))

        val out = adapter.resolve(listOf(history, current))

        assertEquals(2, out.size)
        val crossRound = out[0] as ChatMessage.User
        val ph = crossRound.parts[0] as ContentPart.Text
        assertTrue(ph.text.startsWith("[image] local fileId=550e8400"))
        assertEquals(0, archive.resolveCount)  // 跨 round 不读盘
    }

    @Test
    fun `resolve last User parts order preserved when Local expands mid-list`() = runTest {
        val archive = SpyArchive(backing = mutableMapOf("id1" to "B64"))
        val adapter = DefaultModalityAdapter(archive)
        val local = MediaSource.Local("id1", "image/png")
        val lastUser = ChatMessage.User(
            listOf(
                ContentPart.Text("caption"),
                ContentPart.Image(local),
                httpPart,
            ),
        )

        val out = adapter.resolve(listOf(lastUser))

        val user = out[0] as ChatMessage.User
        assertEquals(4, user.parts.size)
        assertEquals("caption", (user.parts[0] as ContentPart.Text).text)
        assertEquals("[local] fileId=id1", (user.parts[1] as ContentPart.Text).text)
        assertEquals(
            MediaSource.Data("image/png", "B64"),
            (user.parts[2] as ContentPart.Image).source,
        )
        assertEquals(httpPart, user.parts[3])
    }

    @Test
    fun `resolve System and Assistant pass through unchanged`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val messages = listOf(
            ChatMessage.System("you are helpful"),
            ChatMessage.Assistant("ok"),
            ChatMessage.User(listOf(dataPart)),  // last User, 不动
        )

        val out = adapter.resolve(messages)

        assertEquals(3, out.size)
        assertEquals(ChatMessage.System("you are helpful"), out[0])
        assertEquals(ChatMessage.Assistant("ok"), out[1])
        assertEquals(messages[2], out[2])
    }

    @Test
    fun `resolve archive failure propagates as IllegalStateException`() = runTest {
        val throwing = object : MediaArchive {
            override suspend fun store(data: MediaSource.Data) = MediaSource.Local("x", data.mimeType)
            override suspend fun resolve(local: MediaSource.Local): MediaSource.Data =
                throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
        }
        val adapter = DefaultModalityAdapter(throwing)
        val local = MediaSource.Local("x", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        assertFailsWith<IllegalStateException> {
            adapter.resolve(listOf(lastUser))
        }
    }

    // ───────── ToolResultModalityAdapter.adapt() — 拆 ToolResult ─────────

    @Test
    fun `adapt last ToolResult with media splits into text-only ToolResult and synthetic User`() = runTest {
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(ContentPart.Text("result:"), httpPart),
        )

        val out = ToolResultModalityAdapter.adapt(listOf(tr))

        assertEquals(2, out.size)
        // 1. text-only ToolResult
        val textOnly = out[0] as ChatMessage.ToolResult
        assertEquals(listOf<ContentPart>(ContentPart.Text("result:")), textOnly.parts)
        // 2. 合成 User 是最后 User, 后续由 resolve 处理
        val synthetic = out[1] as ChatMessage.User
        assertEquals(2, synthetic.parts.size)
        assertEquals("[from echo]", (synthetic.parts[0] as ContentPart.Text).text)
        assertEquals(httpPart, synthetic.parts[1])
    }

    @Test
    fun `adapt last ToolResult with only text passes through unchanged`() = runTest {
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(ContentPart.Text("just text")),
        )

        val out = ToolResultModalityAdapter.adapt(listOf(tr))

        assertEquals(1, out.size)
        assertEquals(tr, out[0])
    }

    @Test
    fun `adapt all ToolResults with media get split`() = runTest {
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(httpPart),
        )
        val user = ChatMessage.User(listOf(ContentPart.Text("next")))

        val out = ToolResultModalityAdapter.adapt(listOf(tr, user))

        // 所有含 media 的 ToolResult 都会被拆分，不再区分是否末尾
        // tr (Image+Http) → text-only ToolResult + synthetic User(Image)
        assertEquals(3, out.size)
        assertTrue(out[0] is ChatMessage.ToolResult)
        assertTrue((out[0] as ChatMessage.ToolResult).parts.all { it is ContentPart.Text })
        assertTrue(out[1] is ChatMessage.User)
        assertEquals(user, out[2])
    }
}
