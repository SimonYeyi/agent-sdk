package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.memory.Memory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SubagentPluginTest {

    private class StubSubagent(
        override val name: String,
        override val description: String = "stub subagent",
        override val maxIterations: Int? = 5,
        override val memory: Memory? = null,
        override val tools: List<Tool>? = null,
    ) : Subagent {
        override suspend fun load(): String = "stub instructions"
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

    @Test
    fun `plugin exposes the same registry passed in constructor`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val plugin = SubagentPlugin(registry)
        assertEquals(registry, plugin.config)
    }

    @Test
    fun `install in delegate mode installs load_subagent tool`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val plugin = SubagentPlugin(registry)
        val context = FakePluginContext()
        plugin.install(context)
        val toolNames = context.tools.map { it.name }
        assertContains(toolNames, "load_subagent")
    }

    @Test
    fun `install in one-to-one mode installs per-subagent tools`() {
        val registry = SubagentRegistry().apply {
            register(StubSubagent("alpha"))
            register(StubSubagent("beta"))
        }
        val plugin = SubagentPlugin(registry, enableDelegateAdaptMode = false)
        val context = FakePluginContext()
        plugin.install(context)
        val toolNames = context.tools.map { it.name }
        assertContains(toolNames, "subagent_alpha")
        assertContains(toolNames, "subagent_beta")
        assertFalse("load_subagent" in toolNames)
    }

    @Test
    fun `install respects enableDelegateAdaptMode toggle`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("x")) }
        val delegateContext = FakePluginContext()
        val oneToOneContext = FakePluginContext()
        SubagentPlugin(registry, enableDelegateAdaptMode = true).install(delegateContext)
        SubagentPlugin(registry, enableDelegateAdaptMode = false).install(oneToOneContext)
        assertContains(delegateContext.tools.map { it.name }, "load_subagent")
        assertFalse("load_subagent" in oneToOneContext.tools.map { it.name })
    }
}