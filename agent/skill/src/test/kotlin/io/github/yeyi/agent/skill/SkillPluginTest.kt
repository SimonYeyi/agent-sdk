package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SkillPluginTest {

    private class StubSkill(
        override val name: String,
        override val description: String = "stub skill",
    ) : Skill {
        override suspend fun load(): String = "stub instructions"
    }

    private class FakePluginContext : AgentPluginContext {
        private val _tools = mutableListOf<Tool>()
        val tools: List<Tool> get() = _tools

        override fun registerTool(tool: Tool) {
            _tools.add(tool)
        }

        override fun appendPersona(label: String, content: String) {}
    }

    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    @Test
    fun `install installs load_skill tool`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val installer = SkillPlugin(registry)
        val context = FakePluginContext()
        installer.install(context)
        val toolNames = context.tools.map { it.name }
        assertContains(toolNames, "load_skill")
    }

    @Test
    fun `install does NOT install SkillToolLoader or SkillToolCaller`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val installer = SkillPlugin(registry)
        val context = FakePluginContext()
        installer.install(context)
        val toolNames = context.tools.map { it.name }
        assertFalse("skill_tool_loader" in toolNames)
        assertFalse("skill_tool_caller" in toolNames)
    }
}
