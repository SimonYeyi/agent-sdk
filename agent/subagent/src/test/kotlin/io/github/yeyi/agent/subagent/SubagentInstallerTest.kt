package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SubagentInstallerTest {

    private class StubSubagent(
        override val name: String,
        override val description: String = "stub subagent",
        override val maxIterations: Int? = 5,
        override val memory: Memory? = null,
        override val tools: List<Tool>? = null,
    ) : Subagent {
        override suspend fun load(): String = "stub instructions"
    }

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
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val installer = SubagentInstaller(registry)
        val m = io.github.yeyi.agent.capability.CapabilityInstaller::class.java.getDeclaredMethod("registry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        assertSame(registry, m.invoke(installer))
    }

    @Test
    fun `installOn in delegate mode installs load_subagent tool`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val installer = SubagentInstaller(registry)
        val builder = newBuilder()
        installer.installOn(builder, enableDelegateAdaptMode = true)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_subagent")
    }

    @Test
    fun `installOn in one-to-one mode installs per-subagent tools`() {
        val registry = SubagentRegistry().apply {
            register(StubSubagent("alpha"))
            register(StubSubagent("beta"))
        }
        val installer = SubagentInstaller(registry)
        val builder = newBuilder()
        installer.installOn(builder, enableDelegateAdaptMode = false)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "subagent_alpha")
        assertContains(toolNames, "subagent_beta")
        assertFalse("load_subagent" in toolNames)
    }

    @Test
    fun `installOn respects enableDelegateAdaptMode toggle`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("x")) }
        val delegateBuilder = newBuilder()
        val oneToOneBuilder = newBuilder()
        SubagentInstaller(registry).installOn(delegateBuilder, enableDelegateAdaptMode = true)
        SubagentInstaller(registry).installOn(oneToOneBuilder, enableDelegateAdaptMode = false)
        assertContains(delegateBuilder.installedTools().map { it.name }, "load_subagent")
        assertFalse("load_subagent" in oneToOneBuilder.installedTools().map { it.name })
    }
}