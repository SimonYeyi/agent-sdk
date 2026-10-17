package io.github.yeyi.agent.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentDefinitionTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `serialization with required fields only`() {
        val definition = AgentDefinition(
            name = "testAgent",
            persona = "You are a helpful assistant",
            provider = AgentDefinition.Provider(
                name = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-3-5-sonnet",
                apiKey = "sk-test"
            )
        )

        val jsonStr = json.encodeToString(definition)
        assertNotNull(jsonStr)
        assert(jsonStr.contains("\"name\""))
        assert(jsonStr.contains("\"testAgent\""))
        assert(jsonStr.contains("\"persona\""))
        assert(jsonStr.contains("\"anthropic\""))
    }

    @Test
    fun `serialization with all fields`() {
        val definition = AgentDefinition(
            name = "smartHome",
            version = 1,
            persona = "智能家居助手",
            provider = AgentDefinition.Provider(
                name = "openai",
                baseUrl = "https://api.openai.com",
                model = "gpt-4",
                apiKey = "sk-openai"
            ),
            maxIterations = 30,
            maxMemoryRounds = 30,
            tools = listOf("getWeather", "controlTV"),
            toolsets = listOf("iotToolset"),
            skills = listOf("weatherSkill"),
            subagents = listOf("helpSubagent"),
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "filesystem",
                    description = "文件系统操作",
                    transport = AgentDefinition.Mcp.Transport.Stdio(
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
                    )
                )
            )
        )

        val jsonStr = json.encodeToString(definition)
        val decoded = json.decodeFromString<AgentDefinition>(jsonStr)

        assertEquals("smartHome", decoded.name)
        assertEquals(1, decoded.version)
        assertEquals("智能家居助手", decoded.persona)
        assertEquals("openai", decoded.provider.name)
        assertEquals(30, decoded.maxIterations)
        assertEquals(listOf("getWeather", "controlTV"), decoded.tools)
        assertEquals(listOf("iotToolset"), decoded.toolsets)
        assertEquals(1, decoded.mcps.size)
        assertEquals("filesystem", decoded.mcps[0].name)
    }

    @Test
    fun `null semantics for tools toolsets skills subagents`() {
        val definition = AgentDefinition(
            name = "testAgent",
            persona = "Test persona",
            provider = AgentDefinition.Provider(
                name = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-3-5-sonnet",
                apiKey = "sk-test"
            ),
            tools = null,
            toolsets = null,
            skills = null,
            subagents = null
        )

        assertEquals(null, definition.tools)
        assertEquals(null, definition.toolsets)
        assertEquals(null, definition.skills)
        assertEquals(null, definition.subagents)

        val jsonStr = json.encodeToString(definition)
        val decoded = json.decodeFromString<AgentDefinition>(jsonStr)
        assertEquals(null, decoded.tools)
        assertEquals(null, decoded.toolsets)
        assertEquals(null, decoded.skills)
        assertEquals(null, decoded.subagents)
    }

    @Test
    fun `empty list semantics`() {
        val definition = AgentDefinition(
            name = "testAgent",
            persona = "Test persona",
            provider = AgentDefinition.Provider(
                name = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-3-5-sonnet",
                apiKey = "sk-test"
            ),
            tools = emptyList(),
            toolsets = emptyList(),
            skills = emptyList(),
            subagents = emptyList()
        )

        assertEquals(emptyList<String>(), definition.tools)
        assertEquals(emptyList<String>(), definition.toolsets)
        assertEquals(emptyList<String>(), definition.skills)
        assertEquals(emptyList<String>(), definition.subagents)
    }

    @Test
    fun `Mcp transport Stdio serialization`() {
        val mcp = AgentDefinition.Mcp(
            name = "stdioMcp",
            description = "Stdio MCP",
            transport = AgentDefinition.Mcp.Transport.Stdio(
                command = "node",
                args = listOf("--version"),
                env = mapOf("NODE_ENV" to "test")
            )
        )

        val jsonStr = json.encodeToString(mcp)
        val decoded = json.decodeFromString<AgentDefinition.Mcp>(jsonStr)

        assertEquals("stdioMcp", decoded.name)
        val transport = decoded.transport as AgentDefinition.Mcp.Transport.Stdio
        assertEquals("node", transport.command)
        assertEquals(listOf("--version"), transport.args)
        assertEquals(mapOf("NODE_ENV" to "test"), transport.env)
    }

    @Test
    fun `Mcp transport Http serialization`() {
        val mcp = AgentDefinition.Mcp(
            name = "httpMcp",
            description = "HTTP MCP",
            transport = AgentDefinition.Mcp.Transport.Http(
                url = "https://mcp.example.com",
                headers = mapOf("Authorization" to "Bearer token")
            )
        )

        val jsonStr = json.encodeToString(mcp)
        val decoded = json.decodeFromString<AgentDefinition.Mcp>(jsonStr)

        assertEquals("httpMcp", decoded.name)
        val transport = decoded.transport as AgentDefinition.Mcp.Transport.Http
        assertEquals("https://mcp.example.com", transport.url)
        assertEquals(mapOf("Authorization" to "Bearer token"), transport.headers)
    }

    @Test
    fun `default values`() {
        val definition = AgentDefinition(
            name = "testAgent",
            persona = "Test persona",
            provider = AgentDefinition.Provider(
                name = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-3-5-sonnet",
                apiKey = "sk-test"
            )
        )

        assertEquals(0, definition.version)
        assertEquals(20, definition.maxIterations)
        assertEquals(20, definition.maxMemoryRounds)
        assertEquals(emptyList(), definition.mcps)
    }
}