package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.modality.ToolResultAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 三组测试, 分别覆盖:
 * - [ModalityAdapter.archive]: write 边 Data → Local(阈值规则)
 * - [DefaultModalityAdapter.resolve]: read 边 Local → Data(末轮消息可见)
 * - [ToolResultAdapter.adapt]: 所有 ToolResult 含 media 时拆 text + 合成 User
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

    // ───────── resolve() — read 边 Local → Data(末轮消息可见) ─────────

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
    fun `resolve last-round ToolResult with Local gets resolved to Data and split`() = runTest {
        // resolve() 内部在 subclass resolve 之后还会跑 toolResultAdapter.adapt(),
        // 所以末轮 ToolResult 最终输出是 text-only TR + synthetic User,
        // synthetic User 内的 Image 应是已 resolve 的 Data(走完 Local → Data 全链路)
        val archive = SpyArchive(backing = mutableMapOf("550e8400..." to "BASE64BYTES"))
        val adapter = DefaultModalityAdapter(archive)
        val local = MediaSource.Local("550e8400...", "image/jpeg")
        val messages = listOf(
            ChatMessage.User(listOf(ContentPart.Text("old"))),                // 跨 round, 占位
            ChatMessage.User(listOf(ContentPart.Text("current"))),             // last User
            ChatMessage.Assistant("thought"),                                  // 末轮 Assistant, 透传
            ChatMessage.ToolResult(
                toolCallId = "c1",
                toolName = "fetch",
                parts = listOf(ContentPart.Image(local)),
            ),
        )

        val out = adapter.resolve(messages)

        // 全链路后: 5 条 (User 占位, User 透传, Assistant 透传, textOnly TR, synthetic User)
        assertEquals(5, out.size)
        assertEquals(ContentPart.Text("old"), (out[0] as ChatMessage.User).parts.single())
        assertEquals(ContentPart.Text("current"), (out[1] as ChatMessage.User).parts.single())
        assertEquals(ChatMessage.Assistant("thought"), out[2])

        // textOnly TR: 原 Image 已剥离, 只剩 [local] fileId 文本 part
        val textOnlyTr = out[3] as ChatMessage.ToolResult
        assertEquals(1, textOnlyTr.parts.size)
        assertEquals(
            "[local] fileId=550e8400...",
            (textOnlyTr.parts.single() as ContentPart.Text).text,
        )

        // synthetic User 拿到 resolved Data — 证明 resolve 链路打通了
        val synthetic = out[4] as ChatMessage.User
        assertEquals(2, synthetic.parts.size)
        assertEquals("[from fetch]", (synthetic.parts[0] as ContentPart.Text).text)
        val data = (synthetic.parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals("BASE64BYTES", data.base64)
        assertEquals(1, archive.resolveCount)
    }

    @Test
    fun `resolve cross-round ToolResult with Local gets placeholdered`() = runTest {
        // 跨 round ToolResult(在 last User 之前)的 Local 转占位文本, 不读盘
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val local = MediaSource.Local(
            fileId = "550e8400-e29b-41d4-a716-446655440000",
            mimeType = "image/jpeg",
        )
        val messages = listOf(
            ChatMessage.User(listOf(ContentPart.Text("first"))),
            ChatMessage.ToolResult(
                toolCallId = "old",
                toolName = "fetch",
                parts = listOf(ContentPart.Text("old result:"), ContentPart.Image(local)),
            ),
            ChatMessage.User(listOf(ContentPart.Text("current"))),  // last User
        )

        val out = adapter.resolve(messages)

        // 跨 round ToolResult(1): text 保留, Image 转占位文本 (走 toTextMessage)
        val tr = out[1] as ChatMessage.ToolResult
        assertEquals(2, tr.parts.size)
        assertEquals("old result:", (tr.parts[0] as ContentPart.Text).text)
        val placeholder = (tr.parts[1] as ContentPart.Text).text
        assertTrue(
            placeholder.startsWith("[image] local fileId=550e8400"),
            "expected image placeholder, got: $placeholder",
        )
        // 跨 round 不触发 MediaArchive.resolve
        assertEquals(0, archive.resolveCount)
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

    // ───────── freshData 缓冲 — 同轮 archive→resolve 命中 ─────────

    @Test
    fun `resolve Local from same-turn archive hits freshData without media archive resolve`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val fresh = MediaSource.Data("image/jpeg", "X".repeat(2048))
        val archived = adapter.archive(ChatMessage.User(listOf(ContentPart.Image(fresh)))) as ChatMessage.User
        val fileId = ((archived.parts.single() as ContentPart.Image).source as MediaSource.Local).fileId

        // resolve 同一轮内, freshData 命中, MediaArchive.resolve 不该被调
        val out = adapter.resolve(listOf(archived))
        assertEquals(0, archive.resolveCount)
        assertEquals(1, archive.storeCount)

        // 拿到的是 fresh Data, 而不是 SpyArchive backing 的默认值
        val user = out[0] as ChatMessage.User
        assertEquals(2, user.parts.size)
        assertEquals("[local] fileId=$fileId", (user.parts[0] as ContentPart.Text).text)
        val resolved = (user.parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals("X".repeat(2048), resolved.base64)
    }

    @Test
    fun `freshData accumulates across multiple archive calls before resolve`() = runTest {
        val archive = SpyArchive()
        // 全 visible adapter: 一次 resolve 同时验证多个 freshData entry 都命中
        val adapter = object : ModalityAdapter(archive) {
            override suspend fun resolve(
                messages: List<ChatMessage>,
                resolver: ModalityAdapter.Resolver,
            ): List<ChatMessage> = messages.map { resolver.resolve(it, true) }
        }

        val d1 = "X".repeat(2048)
        val d2 = "Y".repeat(2048)
        val a1 = adapter.archive(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/jpeg", d1)))),
        ) as ChatMessage.User
        val a2 = adapter.archive(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", d2)))),
        ) as ChatMessage.User

        assertEquals(2, archive.storeCount)

        // 一次 resolve 处理两条消息, freshData 两个 entry 都命中
        val out = adapter.resolve(listOf(a1, a2))

        assertEquals(0, archive.resolveCount)
        val r1 = ((out[0] as ChatMessage.User).parts[1] as ContentPart.Image).source as MediaSource.Data
        val r2 = ((out[1] as ChatMessage.User).parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals(d1, r1.base64)
        assertEquals(d2, r2.base64)
    }

    @Test
    fun `resolve falls back to media archive resolve on next turn after freshData cleared`() = runTest {
        val archive = SpyArchive()
        val adapter = DefaultModalityAdapter(archive)
        val original = "X".repeat(2048)
        val archived = adapter.archive(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/jpeg", original)))),
        ) as ChatMessage.User

        // Turn 1: resolve 命中 freshData, MediaArchive.resolve 不该被调
        adapter.resolve(listOf(archived))
        assertEquals(0, archive.resolveCount)

        // Turn 2: freshData 已 clear, 走 MediaArchive.resolve 兜底
        val out = adapter.resolve(listOf(archived))
        assertEquals(1, archive.resolveCount)
        val data = ((out[0] as ChatMessage.User).parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals(original, data.base64)
    }

    // ───────── ToolResultAdapter.adapt() — 拆 ToolResult ─────────

    @Test
    fun `adapt last ToolResult with media splits into text-only ToolResult and synthetic User`() = runTest {
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(ContentPart.Text("result:"), httpPart),
        )

        val out = ToolResultAdapter().adapt(listOf(tr))

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

        val out = ToolResultAdapter().adapt(listOf(tr))

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

        val out = ToolResultAdapter().adapt(listOf(tr, user))

        // 所有含 media 的 ToolResult 都会被拆分，不再区分是否末尾
        // tr (Image+Http) → text-only ToolResult + synthetic User(Image)
        assertEquals(3, out.size)
        assertTrue(out[0] is ChatMessage.ToolResult)
        val textOnlyTr = out[0] as ChatMessage.ToolResult
        assertTrue(textOnlyTr.parts.all { (it as? ContentPart.Text) != null })
        assertTrue(out[1] is ChatMessage.User)
        assertEquals(user, out[2])
    }
}
