package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
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

    @Test
    fun `missing llmProvider throws`() {
        assertFailsWith<IllegalArgumentException> {
            agent { persona(Persona("x")) }
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
                        context: ToolContext
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
    fun `plugin can append persona`() = runTest {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {
                context.appendPersona("test-plugin", "you are a helpful assistant")
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
                context.appendPersona("test-plugin", "value=${cfg.value}")
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
                context.appendPersona("test-plugin", "no config needed")
            }
        }

        val a = agent {
            llmProvider(fakeProvider())
            plugin(testPlugin)
        }

        val r = a.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", r.message.content)
    }
}
