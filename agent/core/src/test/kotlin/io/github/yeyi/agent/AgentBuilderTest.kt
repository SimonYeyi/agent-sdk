package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentBuilderTest {

    private fun fakeProvider() = FakeLlmProvider(
        nonStreamResponses = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    )

    /** 反射读取构建产物持有的 persona 渲染文本（ReActAgent.persona）。 */
    private fun OmniAgent.personaText(): String {
        val f = ReActAgent::class.java.getDeclaredField("persona").apply { isAccessible = true }
        return f.get(this).toString()
    }

    @Test
    fun `missing llmProvider throws`() {
        assertFailsWith<IllegalArgumentException> {
            agent { persona(AgentPersona("x")) }
        }
    }

    @Test
    fun `agent built via DSL can actually run`() = runTest {
        val a = agent {
            llmProvider(fakeProvider())
        }
        val r = a.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", r.message.content)
    }

    // --- plugin() tests ---

    @Test
    fun `plugin can register tools`() = runTest {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.registerTool(object : Tool {
                    override val name: String = "echo"
                    override val description: String = "echo"
                    override val parametersSchema: ToolParameters = ToolParameters.Empty
                    override suspend fun execute(
                        arguments: JsonElement,
                        context: ToolExecutionContext
                    ): ToolExecutionResult = ToolExecutionResult.success("ok")
                })
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val r = a.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", r.message.content)
    }

    @Test
    fun `plugin can add instructions`() = runTest {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.addInstruction("you are a helpful assistant")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val r = a.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", r.message.content)
    }

    @Test
    fun `addInstruction with same label replaces content`() = runTest {
        var retainedContext: AgentPluginContext? = null
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                retainedContext = context
                context.addInstruction("v1", "usage")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val before = a.personaText()
        assertTrue("<usage>" in before)
        assertTrue("v1" in before)

        // 插件持有 context，后续更新自己的具名段落 —— 原位替换
        retainedContext!!.addInstruction("v2", "usage")

        val after = a.personaText()
        assertTrue("v1" !in after)
        assertTrue("v2" in after)
        assertEquals(1, Regex("<usage>").findAll(after).count())
    }

    @Test
    fun `addInstruction same label from different plugins later one wins`() = runTest {
        fun pluginWith(id: String, content: String) = object : AgentPlugin<Unit> {
            override val id = id
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.addInstruction(content, "usage")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(pluginWith("pluginA", "A content"))
            plugin(pluginWith("pluginB", "B content"))
        }

        // label 全局共享，后写入者原位替换先到者
        val text = a.personaText()
        assertTrue("A content" !in text)
        assertTrue("B content" in text)
        assertEquals(1, Regex("<usage>").findAll(text).count())
    }

    @Test
    fun `addInstruction without label appends anonymously`() = runTest {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.addInstruction("first")
                context.addInstruction("second")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val text = a.personaText()
        assertTrue("first" in text)
        assertTrue("second" in text)
    }

    @Test
    fun `plugin with configure block`() = runTest {
        var configuredValue: String? = null

        data class Config(var value: String = "")

        val testPlugin = object : AgentPlugin<Config> {
            private var cfg = Config()
            override val id = "test"
            override fun configure(block: Config.() -> Unit) {
                cfg.block()
            }
            override fun install(context: AgentPluginContext) {
                configuredValue = cfg.value
                context.addInstruction("value=${cfg.value}")
            }
        }

        agent {
            llmProvider(fakeProvider())
            plugin(testPlugin) {
                value = "configured"
            }
        }

        assertEquals("configured", configuredValue)
    }

    @Test
    fun `plugin with no configure block`() = runTest {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.addInstruction("no config needed")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val r = a.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", r.message.content)
    }

    @Test
    fun `findPlugin returns registered plugin by id`() {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {}
        }

        val b = AgentBuilder().apply { plugin(testPlugin) }

        kotlin.test.assertSame(testPlugin, b.findPlugin("test"))
    }

    @Test
    fun `findPlugin returns null for unknown id`() {
        val b = AgentBuilder()
        kotlin.test.assertNull(b.findPlugin("nope"))
    }

    @Test
    fun `configure runs at plugin registration time not build time`() {
        var configured = false
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) { configured = true }
            override fun install(context: AgentPluginContext) {}
        }

        val b = AgentBuilder().apply { plugin(testPlugin) }
        kotlin.test.assertTrue(configured, "configure should have run inside plugin()")
    }
}
