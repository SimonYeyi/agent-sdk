package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.fakes.FakeAgentHook
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.memory.Memory
import kotlin.test.Test
import kotlin.test.assertContains

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
        override val hookPipeline: AgentHook = FakeAgentHook
        private val _tools = mutableListOf<Tool>()
        val tools: List<Tool> get() = _tools

        override fun registerTool(tool: Tool) {
            _tools.add(tool)
        }

        override fun addInstruction(content: String, label: String?) {
            // not used in tests
        }
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
}