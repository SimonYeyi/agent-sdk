package io.github.yeyi.agent.config

import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentScopeTest {

    private fun createTestDefinition(
        tools: List<String>? = null,
        toolsets: List<String>? = null,
        skills: List<String>? = null,
        subagents: List<String>? = null,
        mcps: List<AgentDefinition.Mcp>? = null
    ) = AgentDefinition(
        name = "testAgent",
        persona = "Test persona",
        provider = AgentDefinition.Provider.Anthropic(
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-5-sonnet",
            apiKey = "sk-test"
        ),
        tools = tools,
        toolsets = toolsets,
        skills = skills,
        subagents = subagents,
        mcps = mcps
    )

    private fun createTool(name: String) = object : Tool {
        override val name: String = name
        override val description: String = "Tool $name"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success(name)
    }

    private fun createSkill(name: String) = object : Skill {
        override val name: String = name
        override val description: String = "Skill $name"
        override suspend fun load(): String = "Skill $name content"
    }

    private fun createSubagent(name: String) = object : Subagent {
        override val name: String = name
        override val description: String = "Subagent $name"
        override val maxIterations: Int? = null
        override val memory: io.github.yeyi.agent.memory.Memory? = null
        override val tools: List<Tool>? = null
        override suspend fun load(): String = "Subagent $name content"
    }

    @Test
    fun `resolveToolRegistries with null tools returns all from both registries`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("tool1"))
            register(createTool("tool2"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazy1")))
        }
        val scope = AgentScope(memory = InMemoryMemory(), 
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = null)

        val (reg, lazyReg) = scope.resolveToolRegistries(definition)

        assertEquals(2, reg!!.all().size)
        assertEquals(1, lazyReg!!.all().size)
    }

    @Test
    fun `resolveToolRegistries with emptyList tools returns null for both`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("tool1"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazy1")))
        }
        val scope = AgentScope(memory = InMemoryMemory(), 
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = emptyList())

        val (reg, lazyReg) = scope.resolveToolRegistries(definition)

        assertTrue(reg == null || reg.all().isEmpty())
        assertNull(lazyReg)
    }

    @Test
    fun `resolveToolRegistries with specific tools filters correctly`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("weather"))
            register(createTool("search"))
            register(createTool("calculator"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazyWeather")))
        }
        val scope = AgentScope(memory = InMemoryMemory(), 
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = listOf("weather", "lazyWeather"))

        val (reg, lazyReg) = scope.resolveToolRegistries(definition)

        assertEquals(1, reg!!.all().size)
        assertEquals("weather", reg.all()[0].name)
        assertEquals(1, lazyReg!!.all().size)
        assertEquals("lazyWeather", lazyReg.all()[0].name)
    }

    @Test
    fun `resolveToolRegistries falls back from lazy to tool registry`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("runtimeTool"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazyWeather")))
        }
        val scope = AgentScope(memory = InMemoryMemory(), 
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = listOf("lazyWeather", "runtimeTool"))

        val (reg, lazyReg) = scope.resolveToolRegistries(definition)

        assertEquals(1, reg!!.all().size)
        assertEquals("runtimeTool", reg.all()[0].name)
        assertEquals(1, lazyReg!!.all().size)
        assertEquals("lazyWeather", lazyReg.all()[0].name)
    }

    @Test
    fun `resolveToolRegistries returns null when tool not found in either registry`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("tool1"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazy1")))
        }
        val scope = AgentScope(memory = InMemoryMemory(), 
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = listOf("nonexistent"))

        val (reg, lazyReg) = scope.resolveToolRegistries(definition)

        assertTrue(reg == null || reg.all().isEmpty())
        assertNull(lazyReg)
    }

    @Test
    fun `resolveToolsetRegistry with null toolsets returns all`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
            register(Toolset("ts2", "Toolset 2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = null)

        val result = scope.resolveToolsetRegistry(definition)

        assertEquals(2, result!!.all().size)
    }

    @Test
    fun `resolveToolsetRegistry with emptyList returns null`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = emptyList())

        val result = scope.resolveToolsetRegistry(definition)

        assertNull(result)
    }

    @Test
    fun `resolveToolsetRegistry with specific names filters correctly`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("weather", "Weather"))
            register(Toolset("search", "Search"))
            register(Toolset("calculator", "Calculator"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = listOf("weather", "search"))

        val result = scope.resolveToolsetRegistry(definition)

        assertEquals(2, result!!.all().size)
        assertTrue(result.all().any { it.name == "weather" })
        assertTrue(result.all().any { it.name == "search" })
        assertTrue(result.all().none { it.name == "calculator" })
    }

    @Test
    fun `resolveSkillRegistry with null skills returns all`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(createSkill("skill1"))
            register(createSkill("skill2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), skillRegistry = skillRegistry)
        val definition = createTestDefinition(skills = null)

        val result = scope.resolveSkillRegistry(definition)

        assertEquals(2, result!!.all().size)
    }

    @Test
    fun `resolveSkillRegistry with emptyList returns null`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(createSkill("skill1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), skillRegistry = skillRegistry)
        val definition = createTestDefinition(skills = emptyList())

        val result = scope.resolveSkillRegistry(definition)

        assertNull(result)
    }

    @Test
    fun `resolveSubagentRegistry with null subagents returns all`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(createSubagent("subagent1"))
            register(createSubagent("subagent2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), subagentRegistry = subagentRegistry)
        val definition = createTestDefinition(subagents = null)

        val result = scope.resolveSubagentRegistry(definition)

        assertEquals(2, result!!.all().size)
    }

    @Test
    fun `resolveSubagentRegistry with emptyList returns null`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(createSubagent("subagent1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), subagentRegistry = subagentRegistry)
        val definition = createTestDefinition(subagents = emptyList())

        val result = scope.resolveSubagentRegistry(definition)

        assertNull(result)
    }

    @Test
    fun `resolveMcpRegistry with null mcps returns null`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory(), )
        val definition = createTestDefinition(mcps = null)

        val result = scope.resolveMcpRegistry(definition, null)

        assertNull(result)
    }

    @Test
    fun `resolveMcpRegistry with emptyList mcps returns null`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory(), )
        val definition = createTestDefinition(mcps = emptyList())

        val result = scope.resolveMcpRegistry(definition, null)

        assertNull(result)
    }

    @Test
    fun `resolveMcpRegistry with Http transport creates Mcp`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory(), )
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "httpMcp",
                    description = "HTTP MCP",
                    transport = AgentDefinition.Mcp.Transport.Http(
                        url = "https://mcp.example.com",
                        headers = mapOf("Authorization" to "Bearer token")
                    )
                )
            )
        )

        val result = scope.resolveMcpRegistry(definition, null)

        assertTrue(result != null)
    }

    @Test
    fun `resolveMcpRegistry with Stdio transport creates Mcp`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory(), )
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-filesystem")
                    )
                )
            )
        )

        val result = scope.resolveMcpRegistry(definition, null)

        assertTrue(result != null)
    }
}
