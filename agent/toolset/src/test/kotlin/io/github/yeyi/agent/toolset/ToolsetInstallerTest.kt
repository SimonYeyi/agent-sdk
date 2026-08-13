package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
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

class ToolsetInstallerTest {

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

    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun newBuilder(): AgentBuilder = AgentBuilder().apply { llmProvider(StubLlm) }

    @Test
    fun `installer exposes the same registry passed in`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val installer = ToolsetInstaller(registry)
        val m = io.github.yeyi.agent.capability.CapabilityInstaller::class.java.getDeclaredMethod("registry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        assertEquals(registry, m.invoke(installer))
    }

    @Test
    fun `installOn installs load_toolset and sub_tool_delegate tools`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val installer = ToolsetInstaller(registry)
        val builder = newBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_toolset")
        assertContains(toolNames, "sub_tool_delegate")
    }

    @Test
    fun `installOn wraps ToolDuplicateException into ToolsetsInstallException`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val installer = ToolsetInstaller(registry)
        val builder = newBuilder()
        installer.installOn(builder)
        assertFailsWith<ToolsetsInstallException> {
            installer.installOn(builder)
        }
    }
}