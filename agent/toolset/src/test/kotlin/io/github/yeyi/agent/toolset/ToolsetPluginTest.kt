package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolsetPluginTest {

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            ChatResponse(
                message = ChatMessage.Assistant(content = "ok"),
                finishReason = FinishReason.Stop,
            )
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
            flowOf(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
    }

    private class FakePluginContext : AgentPluginContext {
        private val _tools = mutableListOf<Tool>()
        val tools: List<Tool> get() = _tools

        override fun registerTool(tool: Tool) {
            _tools.add(tool)
        }

        override fun appendPersona(label: String, content: String) {
            // not used in tests
        }
    }

    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    @Test
    fun `install installs load_toolset and member_tool_delegate tools`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val installer = ToolsetPlugin(registry)
        val context = FakePluginContext()
        installer.install(context)
        val toolNames = context.tools.map { it.name }
        assertContains(toolNames, "load_toolset")
        assertContains(toolNames, "member_tool_delegate")
    }
}
