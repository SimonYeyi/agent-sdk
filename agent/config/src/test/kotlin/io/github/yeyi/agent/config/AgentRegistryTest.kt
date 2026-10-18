package io.github.yeyi.agent.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentRegistryTest {

    private fun createTestProvider() = AgentDefinition.Provider.Anthropic(
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
    fun `register and getOrNull single definition`() {
        val registry = AgentRegistry()
        val definition = createDefinition("agent1")

        registry.register(definition)

        val found = registry.getOrNull("agent1")
        assertEquals(definition, found)
    }

    @Test
    fun `getOrNull returns null for non-existent name`() {
        val registry = AgentRegistry()

        val found = registry.getOrNull("nonExistent")

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

        assertEquals(agent1, registry.getOrNull("agent1"))
        assertEquals(agent2, registry.getOrNull("agent2"))
        assertEquals(agent3, registry.getOrNull("agent3"))
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
    fun `getOrNull returns existing after failed registration`() {
        val registry = AgentRegistry()
        val agent1 = createDefinition("duplicate")

        registry.register(agent1)

        assertFailsWith<IllegalStateException> {
            registry.register(agent1)
        }

        assertEquals(agent1, registry.getOrNull("duplicate"))
    }
}