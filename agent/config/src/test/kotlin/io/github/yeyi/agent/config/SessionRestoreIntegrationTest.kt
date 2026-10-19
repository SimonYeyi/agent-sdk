package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.session.AgentReference
import io.github.yeyi.agent.session.Session
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 集成测试：验证「会话恢复 → AgentReference.name → AgentRegistry → AgentScope → spawn」闭环。
 *
 * session 模块通过 test 依赖反向引入，不改变生产依赖方向。
 */
class SessionRestoreIntegrationTest {

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = createTempDir()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createTool(name: String) = object : Tool {
        override val name: String = name
        override val description: String = "Tool $name"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success(name)
    }

    private fun createDefinition(
        name: String = "assistant",
        tools: List<String>? = null
    ) = AgentDefinition(
        name = name,
        persona = "You are a helpful assistant",
        provider = AgentDefinition.Provider.OpenAi(
            baseUrl = "http://localhost:9999",
            model = "gpt-4o-mini",
            apiKey = "test-key"
        ),
        tools = tools
    )

    @Test
    fun `session 恢复闭环：name 引用经 registry 与 scope 可重复重建 agent`() = runTest {
        val registry = AgentRegistry().apply { register(createDefinition(tools = listOf("greet"))) }
        val toolRegistry = ToolRegistry().apply { register(createTool("greet")) }
        val manager = SessionManager(tempDir)

        val created = manager.create("acct-1", "会话一", AgentReference(name = "assistant"))
        val restored = manager.get("acct-1", created.id)

        assertEquals("assistant", restored.agent.name)
        val definition = registry.getOrNull(restored.agent.name)
        assertNotNull(definition)

        fun rebuild(session: Session): Agent {
            val scope = AgentScope(memory = session.memory, toolRegistry = toolRegistry)
            return definition!!.spawn(scope)
        }

        // 创建时与恢复时各构建一次，均可成功
        assertNotNull(rebuild(created))
        assertNotNull(rebuild(restored))

        // 再次从磁盘读回仍可重建（可重复恢复）
        val restoredAgain = manager.get("acct-1", created.id)
        assertNotNull(rebuild(restoredAgain))
    }

    @Test
    fun `恢复能力集合与定义固化列表一致，不跟随注册表增量`() = runTest {
        val registry = AgentRegistry().apply { register(createDefinition(tools = listOf("greet", "search"))) }
        val toolRegistry = ToolRegistry().apply {
            register(createTool("greet"))
            register(createTool("search"))
            register(createTool("extra")) // 注册表新增项，不应进入恢复结果
        }
        val manager = SessionManager(tempDir)
        val session = manager.create("acct-1", "会话一", AgentReference(name = "assistant"))
        val restored = manager.get("acct-1", session.id)

        val scope = AgentScope(memory = restored.memory, toolRegistry = toolRegistry)
        val info = scope.resolver().resolve(registry.getOrNull(restored.agent.name)!!)

        assertEquals(setOf("greet", "search"), info.toolRegistry!!.all().map { it.name }.toSet())
    }
}
