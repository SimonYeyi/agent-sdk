package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.fakes.FakeAgentHook
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedMemoryPluginTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private val toolContext: ToolExecutionContext = ToolExecutionContext("call-1", testAgentContext())

    private class FakePluginContext : AgentPluginContext {
        override val hookPipeline: AgentHook = FakeAgentHook
        val tools = mutableListOf<Tool>()
        val persona = AgentPersona("test")
        override fun registerTool(tool: Tool) {
            tools += tool
        }

        override fun addInstruction(content: String, label: String?) {
            persona.extra(content, label)
        }
    }

    private fun md(
        name: String,
        description: String = "desc",
        body: String = "- $name",
        at: Instant = base,
    ): String = SharedMemory(name, description, at, at).render(body)

    private fun args(
        name: String,
        description: String? = null,
        content: String? = null,
        mode: String? = null,
        oldText: String? = null,
    ): JsonElement = buildJsonObject {
        put("name", name)
        if (description != null) put("description", description)
        if (content != null) put("content", content)
        if (mode != null) put("mode", mode)
        if (oldText != null) put("old_text", oldText)
    }

    private fun ToolExecutionResult.text(): String =
        parts.joinToString("") { (it as? ContentPart.Text)?.text ?: "" }

    /** 子串出现次数;persona 里 `<user-profile>` 必须恒为 1(原位替换,不追加)。 */
    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    private fun FakePluginContext.saveTool(): Tool = tools.first { it.name == "save_shared_memory" }

    private fun FakePluginContext.loadTool(): Tool = tools.first { it.name == "load_shared_memory" }

    /** 插件在 build() 时才 install,所以工具要装完之后从 builder 的 registry 里取。 */
    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun providerReturning(response: String): FakeLlmProvider = FakeLlmProvider(
        nonStreamResponses = listOf(
            ChatResponse(
                message = ChatMessage.Assistant(content = response),
                finishReason = FinishReason.Stop,
            )
        )
    )

    private fun newTempMemoryDir(prefix: String): Path =
        Files.createTempDirectory(prefix).resolve("mem")

    private fun install(
        store: FakeSharedMemoryStore,
        maxFileCharSize: Int = 8_000,
        provider: LlmProvider? = null,
    ): Pair<FakePluginContext, SharedMemoryLoader> {
        val loader = SharedMemoryLoader.create(store, 5)
        val context = FakePluginContext()
        SharedMemoryPlugin(loader, maxFileCharSize, provider).install(context)
        return context to loader
    }

    // ---------- install ----------

    @Test
    fun `install registers exactly the two memory tools`() {
        val (context, _) = install(FakeSharedMemoryStore())

        assertEquals(
            setOf("load_shared_memory", "save_shared_memory"),
            context.tools.map { it.name }.toSet(),
        )
    }

    @Test
    fun `install creates an empty user template when user md is missing`() {
        val store = FakeSharedMemoryStore()

        install(store)

        val raw = assertNotNull(store.contentOf(SharedMemory.RESERVED_USER))
        assertEquals(SharedMemory.RESERVED_USER, SharedMemory.parse("user", raw).name)
        assertEquals("", SharedMemory.bodyOf(raw))
    }

    @Test
    fun `install never overwrites an existing user profile`() {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))

        install(store)

        assertEquals("- 喜欢海鲜", SharedMemory.bodyOf(store.contentOf("user")!!))
    }

    @Test
    fun `an empty profile is not injected into the persona`() {
        val (context, _) = install(FakeSharedMemoryStore())

        assertFalse("<user-profile>" in context.persona.toString())
    }

    @Test
    fun `a non-empty profile is injected exactly once`() {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))

        val (context, _) = install(store)

        val text = context.persona.toString()
        assertEquals(1, text.occurrences("<user-profile>"))
        assertTrue("- 喜欢海鲜" in text, text)
    }

    // ---------- 运行期刷新 ----------

    @Test
    fun `a successful save on user refreshes the persona immediately`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))
        val (context, _) = install(store)
        val before = context.persona.toString()

        val result = context.saveTool().execute(args(name = "user", content = "- 喜欢日料"), toolContext)

        assertFalse(result.isError, result.text())
        val after = context.persona.toString()
        assertTrue(after != before)
        assertTrue("- 喜欢日料" in after, after)
        assertEquals(1, after.occurrences("<user-profile>"))
    }

    @Test
    fun `consecutive saves never accumulate user-profile sections`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))
        val (context, _) = install(store)

        context.saveTool().execute(args(name = "user", content = "- 喜欢日料"), toolContext)
        context.saveTool().execute(args(name = "user", content = "- 喜欢火锅"), toolContext)

        assertEquals(1, context.persona.toString().occurrences("<user-profile>"))
    }

    @Test
    fun `saving another memory leaves the persona untouched`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))
        val (context, _) = install(store)
        val before = context.persona.toString()

        context.saveTool().execute(
            args(name = "travel", description = "旅行偏好", content = "- 喜欢海边"),
            toolContext,
        )

        assertEquals(before, context.persona.toString())
    }

    @Test
    fun `a compressed user write refreshes the persona with the summary`() = runTest {
        val store = FakeSharedMemoryStore()
        store.put(SharedMemory.RESERVED_USER, md("user", body = "- 喜欢海鲜"))
        val summary = "z".repeat(50)
        val (context, _) = install(store, maxFileCharSize = 100, provider = providerReturning(summary))

        val result = context.saveTool().execute(args(name = "user", content = "y".repeat(200)), toolContext)

        assertFalse(result.isError, result.text())
        val text = context.persona.toString()
        assertTrue(summary in text, text)
        assertFalse("y".repeat(20) in text, "the raw over-long input must not reach the persona")
        assertEquals(1, text.occurrences("<user-profile>"))
    }

    // ---------- DSL ----------

    @Test
    fun `the DSL rejects non-positive limits before creating the directory`() {
        val directory = newTempMemoryDir("sm-dsl")

        assertFailsWith<IllegalArgumentException> {
            AgentBuilder().sharedMemory(directory, maxFileCount = 0)
        }
        assertFalse(Files.exists(directory), "parameters must be validated before createDirectories")

        assertFailsWith<IllegalArgumentException> {
            AgentBuilder().sharedMemory(directory, maxFileCharSize = 0)
        }
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `the DSL registers both tools through the builder`() {
        val directory = newTempMemoryDir("sm-wire")
        val builder = AgentBuilder().apply {
            llmProvider(FakeLlmProvider())
            sharedMemory(directory, maxFileCount = 4)
        }

        builder.build()

        assertEquals(
            setOf("load_shared_memory", "save_shared_memory"),
            builder.installedTools().map { it.name }.toSet(),
        )
    }

    @Test
    fun `two agents on the same directory share one snapshot`() = runTest {
        val directory = newTempMemoryDir("sm-shared")
        val first = AgentBuilder().apply {
            llmProvider(FakeLlmProvider())
            sharedMemory(directory, maxFileCount = 4)
        }
        val second = AgentBuilder().apply {
            llmProvider(FakeLlmProvider())
            sharedMemory(directory, maxFileCount = 4)
        }
        first.build()
        second.build()

        val saveTool = first.installedTools().first { it.name == "save_shared_memory" }
        val result = saveTool.execute(
            args(name = "travel", description = "旅行偏好", content = "- 喜欢海边"),
            toolContext,
        )
        assertFalse(result.isError, result.text())

        val loadTool = second.installedTools().first { it.name == "load_shared_memory" }
        assertTrue("- travel：旅行偏好" in loadTool.description, loadTool.description)
    }
}
