package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModalityAdapterTest {

    private val dataPart = ContentPart.Image(MediaSource.Data("image/jpeg", "BASE64"))
    private val httpPart = ContentPart.Image(MediaSource.Http("https://example.com/x.png"))
    private val fileIdPart = ContentPart.Image(MediaSource.FileId("file-abc"))
    private val localPart = ContentPart.Image(
        MediaSource.Local(fileId = "550e8400-e29b-41d4-a716-446655440000", mimeType = "image/jpeg"),
    )

    /** Spy archive: 记录 store/resolve 调用次数,resolve 时按 fileId 找 base64 */
    private class SpyArchive(
        private val backing: MutableMap<String, String> = mutableMapOf(),
        var resolveCount: Int = 0,
        var storeCount: Int = 0,
    ) : MediaArchive {
        override suspend fun store(data: MediaSource.Data): MediaSource.Local {
            storeCount++
            val id = "stored-${storeCount}"
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

    @Test
    fun `last User with Local resolves to Text ref + Data, archive called once`() = runTest {
        val archive = SpyArchive(backing = mutableMapOf("550e8400..." to "BASE64BYTES"))
        // 用一个确定性 fileId 的 Local 让 spy 找到 backing map
        val local = MediaSource.Local("550e8400...", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

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
    fun `last User with Data passes through unchanged`() = runTest {
        val archive = SpyArchive()
        val lastUser = ChatMessage.User(listOf(dataPart))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(listOf(dataPart), user.parts)
        assertEquals(0, archive.resolveCount)
    }

    @Test
    fun `last User with Http and FileId passes through unchanged`() = runTest {
        val archive = SpyArchive()
        val lastUser = ChatMessage.User(listOf(httpPart, fileIdPart))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(listOf(httpPart, fileIdPart), user.parts)
        assertEquals(0, archive.resolveCount)
    }

    @Test
    fun `cross-round User with Local becomes placeholder without archive resolve`() = runTest {
        val archive = SpyArchive()
        val history = ChatMessage.User(listOf(localPart))    // cross-round (i < lastUserIdx)
        val current = ChatMessage.User(listOf(ContentPart.Text("next question")))

        val out = DefaultModalityAdapter().adapt(listOf(history, current), archive)

        assertEquals(2, out.size)
        val crossRound = out[0] as ChatMessage.User
        val ph = crossRound.parts[0] as ContentPart.Text
        assertTrue(ph.text.startsWith("[image] local fileId=550e8400"))
        assertTrue(ph.text.endsWith("..."), "expected truncation marker, got: ${ph.text}")
        assertEquals(0, archive.resolveCount)  // 跨 round 不读盘
    }

    @Test
    fun `last User parts order preserved when Local expands mid-list`() = runTest {
        // 输入 [Text, Local, Http], Local 展开为 [Text引用, Data],
        // 期望 [Text, Text引用, Data, Http] (Http 相对 Local 位置保持)
        val archive = SpyArchive(backing = mutableMapOf("id1" to "B64"))
        val local = MediaSource.Local("id1", "image/png")
        val lastUser = ChatMessage.User(
            listOf(
                ContentPart.Text("caption"),
                ContentPart.Image(local),
                httpPart,
            ),
        )

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

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
    fun `last ToolResult with media splits into text-only ToolResult and synthetic User`() = runTest {
        val archive = SpyArchive()
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(ContentPart.Text("result:"), httpPart),
        )

        val out = DefaultModalityAdapter().adapt(listOf(tr), archive)

        assertEquals(2, out.size)
        // 1. text-only ToolResult
        val textOnly = out[0] as ChatMessage.ToolResult
        assertEquals(listOf<ContentPart>(ContentPart.Text("result:")), textOnly.parts)
        // 2. 合成 User 是最后 User
        val synthetic = out[1] as ChatMessage.User
        assertEquals(2, synthetic.parts.size)
        assertEquals("[from echo]", (synthetic.parts[0] as ContentPart.Text).text)
        assertEquals(httpPart, synthetic.parts[1])
    }

    @Test
    fun `System and Assistant pass through unchanged`() = runTest {
        val archive = SpyArchive()
        val messages = listOf(
            ChatMessage.System("you are helpful"),
            ChatMessage.Assistant("ok"),
            ChatMessage.User(listOf(dataPart)),  // last User,不动
        )

        val out = DefaultModalityAdapter().adapt(messages, archive)

        assertEquals(3, out.size)
        assertEquals(ChatMessage.System("you are helpful"), out[0])
        assertEquals(ChatMessage.Assistant("ok"), out[1])
        assertEquals(messages[2], out[2])  // last User unchanged
    }

    @Test
    fun `archive resolve failure propagates as IllegalStateException`() = runTest {
        val throwing = object : MediaArchive {
            override suspend fun store(data: MediaSource.Data) = MediaSource.Local("x", data.mimeType)
            override suspend fun resolve(local: MediaSource.Local): MediaSource.Data =
                throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
        }
        val local = MediaSource.Local("x", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        assertFailsWith<IllegalStateException> {
            DefaultModalityAdapter().adapt(listOf(lastUser), throwing)
        }
    }
}