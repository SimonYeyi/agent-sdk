package io.github.yeyi.agent.session

import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 归档全链路集成测试 —— 从 [SessionRepository] 出发,验证 AgentBuilder 的
 * MediaArchivable 能力检测与 archive/resolve 在真实 ReActAgent 运行中生效:
 *
 * 大 Data 查询 → archive(Data→Local 落 media/ + memory.jsonl) → 请求边界
 * resolve(Local→Data 喂 LLM),同时 conversation 分页视图同步追加。
 */
class SessionAgentIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session-agent-integration").toFile()
        repo = SessionRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `agent built from session archives large Data and resolves back for LLM`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        // SessionExtensions.memory(session) → AgentBuilder 在 build() 时通过
        // `memory as? MediaArchivable` 检测 JsonlMemory,自动注入 DefaultModalityAdapter(archive)
        val agent = agent {
            llmProvider(provider)
            memory(session)
        }

        // 超过 ArchiveAdapter.ARCHIVE_THRESHOLD(1024) 的 base64,触发 Data → Local
        val largeData = MediaSource.Data("image/jpeg", "x".repeat(2048))
        val result = agent.run(
            AgentQuery(
                listOf(
                    ContentPart.Text("see this"),
                    ContentPart.Image(largeData),
                )
            )
        ).awaitResult()
        assertEquals("ok", result.message.content)

        // 1. memory 侧落盘形态是 Local —— archive 生效
        val stored = session.memory.history()[0].message as ChatMessage.User
        val storedSrc = (stored.parts[1] as ContentPart.Image).source
        assertTrue(storedSrc is MediaSource.Local,
            "stored source should be Local after archive, got ${storedSrc::class.simpleName}")

        // 2. media/ 下存在真实归档文件 —— FilesystemMediaArchive.store 生效
        val mediaFiles = File(File(File(tempDir, "agent/sessions/alice"), session.id), "media")
            .listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(1, mediaFiles.size, "media dir should hold exactly one archived file")

        // 3. LLM 请求边界还原为 Data —— resolve 生效(同轮 freshData 命中)
        val requestUser = provider.recordedRequests[0].messages
            .filterIsInstance<ChatMessage.User>().first()
        val llmSrc = (requestUser.parts.filterIsInstance<ContentPart.Image>().last().source)
        assertTrue(llmSrc is MediaSource.Data,
            "LLM should receive Data after resolve, got ${llmSrc::class.simpleName}")
        assertEquals(largeData.base64, (llmSrc as MediaSource.Data).base64)

        // 4. conversation 分页视图同步追加同一条(含 Local) —— add() 副作用生效
        // agent 运行还会追加 Assistant 响应,故共 2 条;首条即归档后的 User
        val conversationAll = session.conversation.history(Conversation.PAGE_ALL)
        assertEquals(2, conversationAll.size, "conversation should mirror memory writes")
        val convSrc = (conversationAll[0].message as ChatMessage.User).parts[1] as ContentPart.Image
        assertEquals((storedSrc as MediaSource.Local).fileId, (convSrc.source as MediaSource.Local).fileId)
    }
}
