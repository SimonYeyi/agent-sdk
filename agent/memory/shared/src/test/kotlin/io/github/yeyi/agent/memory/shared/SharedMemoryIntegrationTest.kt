package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 从 DSL 到 LLM 工具闭环的接缝测试。
 *
 * 断言点放在磁盘与工具返回值上,不断言模型输出文本(脚本是写死的,断言它没有信息量)。
 *
 * 每个用例都新建临时目录:[SharedMemoryLoader.instances] 是进程级注册表,
 * 同一个真实路径会在测试之间串场,而且永远清理不掉。
 */
class SharedMemoryIntegrationTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun tempDir(): Path = Files.createTempDirectory("sm-integration")

    private fun md(name: String, description: String = "desc", body: String = "- $name"): String =
        SharedMemory(name, description, base, base).render(body)

    private fun args(
        name: String,
        description: String? = null,
        content: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        if (description != null) put("description", description)
        if (content != null) put("content", content)
    }

    private fun toolCall(id: String, name: String, arguments: JsonObject): ChatResponse =
        ChatResponse(
            message = ChatMessage.Assistant(toolCalls = listOf(ToolCall(id, name, arguments))),
            finishReason = FinishReason.ToolCalls,
        )

    private fun finalAnswer(text: String = "done"): ChatResponse =
        ChatResponse(ChatMessage.Assistant(content = text), finishReason = FinishReason.Stop)

    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    /** 目录里的记忆文件(排除 user.md)。 */
    private fun memoryFiles(directory: Path): List<String> =
        directory.toFile().listFiles()
            ?.filter { it.extension == "md" && it.name != "${SharedMemory.RESERVED_USER}.md" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun bodyOf(directory: Path, name: String): String =
        SharedMemory.bodyOf(Files.readString(directory.resolve("$name.md")))

    @Test
    fun `the DSL registers both tools on a built agent`() {
        val builder = AgentBuilder().apply {
            llmProvider(FakeLlmProvider())
            sharedMemory(tempDir(), maxFileCount = 4)
        }

        builder.build()

        assertEquals(
            setOf("load_shared_memory", "save_shared_memory"),
            builder.installedTools().map { it.name }.toSet(),
        )
    }

    @Test
    fun `save then load round-trip through a real agent`() = runTest {
        val directory = tempDir()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                toolCall(
                    "c1",
                    "save_shared_memory",
                    args(name = "travel", description = "旅行偏好", content = "- 喜欢海边"),
                ),
                toolCall("c2", "load_shared_memory", args(name = "travel")),
                finalAnswer(),
            )
        )
        val agent = AgentBuilder().apply {
            llmProvider(provider)
            sharedMemory(directory, maxFileCount = 4)
        }.build()

        agent.run(AgentQuery.text("记住我喜欢海边")).awaitResult()

        assertEquals(listOf("travel"), memoryFiles(directory))
        val body = bodyOf(directory, "travel")
        assertEquals("- 喜欢海边", body)

        // 第二轮的工具结果文本就是磁盘上的正文(load 只返回 body,不含 frontmatter)
        val loadResult = provider.recordedRequests[2].messages
            .filterIsInstance<ChatMessage.ToolResult>()
            .single { it.toolName == "load_shared_memory" }
        assertEquals(body, loadResult.parts.text)
    }

    @Test
    fun `a pre-existing user profile reaches the system prompt`() = runTest {
        val directory = tempDir()
        Files.writeString(directory.resolve("user.md"), md("user", "画像", body = "- 喜欢海鲜"))
        val provider = FakeLlmProvider(nonStreamResponses = listOf(finalAnswer()))

        AgentBuilder().apply {
            llmProvider(provider)
            sharedMemory(directory)
        }.build().run(AgentQuery.text("hi")).awaitResult()

        val system = provider.recordedRequests[0].messages.first() as ChatMessage.System
        assertTrue("- 喜欢海鲜" in system.content, system.content)
    }

    @Test
    fun `a runtime save on user shows up in the very next request`() = runTest {
        val directory = tempDir()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                toolCall("c1", "save_shared_memory", args(name = "user", content = "- 喜欢日料")),
                finalAnswer(),
            )
        )

        AgentBuilder().apply {
            llmProvider(provider)
            sharedMemory(directory)
        }.build().run(AgentQuery.text("以后多给我推荐日料")).awaitResult()

        assertEquals("- 喜欢日料", bodyOf(directory, "user"))
        val secondSystem = provider.recordedRequests[1].messages.first() as ChatMessage.System
        assertTrue("- 喜欢日料" in secondSystem.content, secondSystem.content)
    }

    @Test
    fun `compression uses the provider passed to the DSL`() = runTest {
        val directory = tempDir()
        val summary = "z".repeat(40)
        val compressor = FakeLlmProvider(
            nonStreamResponses = listOf(finalAnswer(text = summary))
        )
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                toolCall(
                    "c1",
                    "save_shared_memory",
                    args(name = "travel", description = "旅行偏好", content = "y".repeat(200)),
                ),
                finalAnswer(),
            )
        )

        AgentBuilder().apply {
            llmProvider(provider)
            sharedMemory(directory, maxFileCharSize = 100, llmProvider = compressor)
        }.build().run(AgentQuery.text("记住这些")).awaitResult()

        assertEquals(summary, bodyOf(directory, "travel"))
        assertEquals(1, compressor.recordedRequests.size)
    }

    @Test
    fun `saving past the count limit evicts the oldest memory in a real run`() = runTest {
        val directory = tempDir()
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                toolCall("c1", "save_shared_memory", args(name = "a", description = "d", content = "- a")),
                toolCall("c2", "save_shared_memory", args(name = "b", description = "d", content = "- b")),
                toolCall("c3", "save_shared_memory", args(name = "c", description = "d", content = "- c")),
                finalAnswer(),
            )
        )

        AgentBuilder().apply {
            llmProvider(provider)
            sharedMemory(directory, maxFileCount = 2)
        }.build().run(AgentQuery.text("记三件事")).awaitResult()

        assertEquals(listOf("b", "c"), memoryFiles(directory))
    }

    @Test
    fun `two agents on the same directory see each other's memories`() = runTest {
        val directory = tempDir()
        val firstProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                toolCall(
                    "c1",
                    "save_shared_memory",
                    args(name = "travel", description = "旅行偏好", content = "- 喜欢海边"),
                ),
                finalAnswer(),
            )
        )

        AgentBuilder().apply {
            llmProvider(firstProvider)
            sharedMemory(directory, maxFileCount = 4)
        }.build().run(AgentQuery.text("记住")).awaitResult()

        val second = AgentBuilder().apply {
            llmProvider(FakeLlmProvider())
            sharedMemory(directory, maxFileCount = 4)
        }
        second.build()

        val loadTool = second.installedTools().first { it.name == "load_shared_memory" }
        assertTrue("- travel：旅行偏好" in loadTool.description, loadTool.description)
    }
}
