package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillPluginTest {

    private class StubSkill(
        override val name: String,
        override val description: String = "stub skill",
    ) : Skill {
        override suspend fun load(): String = "stub instructions"
    }

    private class StubSkillTool(override val name: String) : Tool {
        override val description: String = "stub tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("")
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
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val installer = SkillPlugin(registry)
        val m = io.github.yeyi.agent.capability.CapabilityPlugin::class.java.getDeclaredMethod("registry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        assertEquals(registry, m.invoke(installer))
    }

    @Test
    fun `installOn installs load_skill tool`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val installer = SkillPlugin(registry)
        val builder = newBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_skill")
    }

    @Test
    fun `installOn does NOT install SkillToolLoader or SkillToolCaller when registry has no tools`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val installer = SkillPlugin(registry)
        val builder = newBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertFalse("skill_tool_loader" in toolNames)
        assertFalse("skill_tool_caller" in toolNames)
    }

    @Test
    fun `installOn installs SkillToolLoader and SkillToolCaller when registry has tools`() {
        val registry = SkillRegistry().apply {
            register(StubSkill("alpha"))
            registerTools(listOf(StubSkillTool("helper_a")))
        }
        val installer = SkillPlugin(registry)
        val builder = newBuilder()
        installer.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "skill_tool_loader")
        assertContains(toolNames, "skill_tool_caller")
    }
}