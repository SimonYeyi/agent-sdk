package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
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
import kotlin.test.assertTrue

class AgentDefinitionExtensionsTest {

    private fun createTestProvider() = AgentDefinition.Provider(
        name = "anthropic",
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-5-sonnet",
        apiKey = "sk-test"
    )

    private fun createTestDefinition(
        tools: List<String>? = null,
        toolsets: List<String>? = null,
        skills: List<String>? = null,
        subagents: List<String>? = null,
        mcps: List<AgentDefinition.Mcp> = emptyList()
    ) = AgentDefinition(
        name = "testAgent",
        persona = "Test persona",
        provider = createTestProvider(),
        tools = tools,
        toolsets = toolsets,
        skills = skills,
        subagents = subagents,
        mcps = mcps
    )

    @Test
    fun `spawn creates agent with memory and provider`() = runTest {
        val definition = createTestDefinition()
        val memory = InMemoryMemory()

        val agent = definition.spawn(memory)

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with null tools uses all from registries`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(object : Tool {
                override val name: String = "tool1"
                override val description: String = "Tool 1"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("tool1")
            })
            register(object : Tool {
                override val name: String = "tool2"
                override val description: String = "Tool 2"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("tool2")
            })
        }

        val definition = createTestDefinition(tools = null)
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolRegistry = toolRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with emptyList tools uses none`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(object : Tool {
                override val name: String = "shouldNotBeAdded"
                override val description: String = "Should not be added"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("shouldNotBeAdded")
            })
        }

        val definition = createTestDefinition(tools = emptyList())
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolRegistry = toolRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with specific tool names filters registry`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(object : Tool {
                override val name: String = "weather"
                override val description: String = "Get weather"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("weather")
            })
            register(object : Tool {
                override val name: String = "search"
                override val description: String = "Web search"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("search")
            })
            register(object : Tool {
                override val name: String = "calculator"
                override val description: String = "Calculator"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("calculator")
            })
        }

        val definition = createTestDefinition(tools = listOf("weather", "search"))
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolRegistry = toolRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with null toolsets uses all from registry`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("toolset1", "Toolset 1").apply {
                add(object : Tool {
                    override val name: String = "ts1_tool1"
                    override val description: String = "Toolset 1 Tool 1"
                    override val parametersSchema: ToolParameters = ToolParameters.Empty
                    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                        ToolExecutionResult.success("ts1_tool1")
                })
            })
            register(Toolset("toolset2", "Toolset 2").apply {
                add(object : Tool {
                    override val name: String = "ts2_tool1"
                    override val description: String = "Toolset 2 Tool 1"
                    override val parametersSchema: ToolParameters = ToolParameters.Empty
                    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                        ToolExecutionResult.success("ts2_tool1")
                })
            })
        }

        val definition = createTestDefinition(toolsets = null)
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolsetRegistry = toolsetRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with emptyList toolsets uses none`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("shouldNotBeAdded", "Should not be added"))
        }

        val definition = createTestDefinition(toolsets = emptyList())
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolsetRegistry = toolsetRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with specific toolset names filters registry`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("weather", "Weather toolset"))
            register(Toolset("search", "Search toolset"))
            register(Toolset("calculator", "Calculator toolset"))
        }

        val definition = createTestDefinition(toolsets = listOf("weather", "search"))
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolsetRegistry = toolsetRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with null skills uses all from registry`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(object : Skill {
                override val name: String = "skill1"
                override val description: String = "Skill 1"
                override suspend fun load(): String = "Skill 1 content"
            })
            register(object : Skill {
                override val name: String = "skill2"
                override val description: String = "Skill 2"
                override suspend fun load(): String = "Skill 2 content"
            })
        }

        val definition = createTestDefinition(skills = null)
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            skillRegistry = skillRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with emptyList skills uses none`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(object : Skill {
                override val name: String = "shouldNotBeAdded"
                override val description: String = "Should not be added"
                override suspend fun load(): String = "Should not be added"
            })
        }

        val definition = createTestDefinition(skills = emptyList())
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            skillRegistry = skillRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with null subagents uses all from registry`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(object : Subagent {
                override val name: String = "subagent1"
                override val description: String = "Subagent 1"
                                        override val maxIterations: Int? = null
                override val memory: io.github.yeyi.agent.memory.Memory? = null
                override val tools: List<Tool>? = null
                override suspend fun load(): String = "Subagent 1 content"
            })
            register(object : Subagent {
                override val name: String = "subagent2"
                override val description: String = "Subagent 2"
                override val maxIterations: Int? = null
                override val memory: io.github.yeyi.agent.memory.Memory? = null
                override val tools: List<Tool>? = null
                override suspend fun load(): String = "Subagent 2 content"
            })
        }

        val definition = createTestDefinition(subagents = null)
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            subagentRegistry = subagentRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with emptyList subagents uses none`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(object : Subagent {
                override val name: String = "shouldNotBeAdded"
                override val description: String = "Should not be added"
                override val maxIterations: Int? = null
                override val memory: io.github.yeyi.agent.memory.Memory? = null
                override val tools: List<Tool>? = null
                override suspend fun load(): String = "Should not be added"
            })
        }

        val definition = createTestDefinition(subagents = emptyList())
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            subagentRegistry = subagentRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn uses lazyToolRegistry first then toolRegistry for tool lookup`() = runTest {
        val weatherTool = object : Tool {
            override val name: String = "lazyWeather"
            override val description: String = "Lazy weather tool"
            override val parametersSchema: ToolParameters = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.success("lazyWeather")
        }

        val lazyToolRegistry = LazyToolRegistry().apply {
            register(io.github.yeyi.agent.tool.lazy_loading.LazyTool(weatherTool))
        }

        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(object : Tool {
                override val name: String = "runtimeTool"
                override val description: String = "Runtime tool"
                override val parametersSchema: ToolParameters = ToolParameters.Empty
                override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                    ToolExecutionResult.success("runtimeTool")
            })
        }

        val definition = createTestDefinition(tools = listOf("lazyWeather", "runtimeTool"))
        val memory = InMemoryMemory()

        val agent = definition.spawn(
            memory = memory,
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with all null registries creates agent with empty filtered registries`() = runTest {
        val definition = createTestDefinition(
            tools = null,
            toolsets = null,
            skills = null,
            subagents = null
        )
        val memory = InMemoryMemory()

        val agent = definition.spawn(memory)

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with Http MCP transport creates Mcp`() = runTest {
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
        val memory = InMemoryMemory()

        val agent = definition.spawn(memory)

        assertTrue(agent is Agent)
    }

    @Test
    fun `spawn with Stdio MCP transport creates Mcp`() = runTest {
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
                        env = mapOf("NODE_ENV" to "production")
                    )
                )
            )
        )
        val memory = InMemoryMemory()

        val agent = definition.spawn(memory)

        assertTrue(agent is Agent)
    }
}