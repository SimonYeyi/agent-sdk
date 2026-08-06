package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRegistryTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override suspend fun load(): String = content
    }

    private class FixedTool(
        override val name: String,
        override val description: String = "test tool",
    ) : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    private fun emptyContext(): SkillContext = SkillContext()

    @Test
    fun `registry capabilityName is skill`() {
        val r = SkillRegistry()
        assertEquals(Skill.CAPABILITY_TYPE, r.capabilityType)
        assertEquals("skill", r.capabilityType)
    }

    @Test
    fun `register adds skill to registry`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询", "body"))
        val names = registry.all().map { it.name }.toSet()
        assertTrue("weather" in names)
    }

    @Test
    fun `register multiple skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("a", "d1", "b1"))
        registry.register(FixedSkill("b", "d2", "b2"))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("a", "b"), names)
    }

    @Test
    fun `register iterable of skills`() {
        val registry = SkillRegistry()
        registry.register(listOf(
            FixedSkill("x", "d1", "b1"),
            FixedSkill("y", "d2", "b2"),
        ))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("x", "y"), names)
    }

    @Test
    fun `duplicate skill name throws`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("dup", "d", "b"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(FixedSkill("dup", "d", "b2"))
        }
    }

    @Test
    fun `all returns all registered skills`() {
        val registry = SkillRegistry()
        val s1 = FixedSkill("weather", "d", "body1")
        val s2 = FixedSkill("news", "d", "body2")
        registry.register(s1)
        registry.register(s2)
        val all = registry.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "weather" })
        assertTrue(all.any { it.name == "news" })
    }

    @Test
    fun `skill can be loaded via all and activate`() = runTest {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        val skill = registry.all().find { it.name == "weather" }
        assertEquals("## weather body", skill?.activate(null, emptyContext()))
    }

    @Test
    fun `unknown skill returns null when searching via all`() {
        val registry = SkillRegistry()
        assertNull(registry.all().find { it.name == "unknown" })
    }

    @Test
    fun `allTools returns empty list when no tools registered`() {
        val registry = SkillRegistry()
        assertEquals(emptyList(), registry.allTools())
    }

    @Test
    fun `allTools returns all registered tools in insertion order`() {
        val registry = SkillRegistry()
        val t1 = FixedTool("alpha")
        val t2 = FixedTool("beta")
        val t3 = FixedTool("gamma")
        registry.registerTools(listOf(t1, t2, t3))
        val names = registry.allTools().map { it.name }
        assertEquals(listOf("alpha", "beta", "gamma"), names)
    }

    @Test
    fun `allTools returns snapshot independent of subsequent mutations`() {
        val registry = SkillRegistry()
        registry.registerTools(listOf(FixedTool("a")))
        val snapshot = registry.allTools()
        registry.registerTools(listOf(FixedTool("b")))
        assertEquals(listOf("a"), snapshot.map { it.name })
        assertEquals(listOf("a", "b"), registry.allTools().map { it.name })
    }
}
