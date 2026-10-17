package io.github.yeyi.agent.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentRegistryTest {

    private fun createTestProvider() = AgentDefinition.Provider(
        name = "anthropic",
        baseUrl = "https://api.anthropic.com",
        model = "claude-3-5-sonnet",
        apiKey = "sk-test"
    )

    private fun createDefinition(name: String) = AgentDefinition(
        name = name,
        persona = "Test persona for $name",
        provider = createTestProvider()
    )

    @Test
    fun `register and find single definition`() {
        val registry = AgentRegistry()
        val definition = createDefinition("agent1")

        registry.register(definition)

        val found = registry.find("agent1")
        assertEquals(definition, found)
    }

    @Test
    fun `find returns null for non-existent name`() {
        val registry = AgentRegistry()

        val found = registry.find("nonExistent")

        assertNull(found)
    }

    @Test
    fun `register multiple definitions`() {
        val registry = AgentRegistry()
        val agent1 = createDefinition("agent1")
        val agent2 = createDefinition("agent2")
        val agent3 = createDefinition("agent3")

        registry.register(agent1)
        registry.register(agent2)
        registry.register(agent3)

        assertEquals(agent1, registry.find("agent1"))
        assertEquals(agent2, registry.find("agent2"))
        assertEquals(agent3, registry.find("agent3"))
    }

    @Test
    fun `register duplicate name throws`() {
        val registry = AgentRegistry()
        val agent1 = createDefinition("duplicate")
        val agent2 = createDefinition("duplicate")

        registry.register(agent1)

        assertFailsWith<IllegalStateException> {
            registry.register(agent2)
        }
    }

    @Test
    fun `find non-existent after failed registration`() {
        val registry = AgentRegistry()
        val agent1 = createDefinition("duplicate")

        registry.register(agent1)

        assertFailsWith<IllegalStateException> {
            registry.register(agent1)
        }

        assertEquals(agent1, registry.find("duplicate"))
    }
}