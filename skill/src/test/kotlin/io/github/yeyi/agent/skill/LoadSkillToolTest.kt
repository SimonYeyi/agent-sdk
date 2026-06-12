package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadSkillToolTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(): String = content
    }

    @Test
    fun `LoadSkillTool NAME is load_skill`() {
        val registry = SkillRegistry()
        assertEquals("load_skill", LoadSkillTool(registry).name)
    }

    @Test
    fun `LoadSkillTool description`() {
        val registry = SkillRegistry()
        assertEquals("加载技能详细指令", LoadSkillTool(registry).description)
    }

    @Test
    fun `LoadSkillTool parameters schema has skill_name`() {
        val registry = SkillRegistry()
        val schema = (LoadSkillTool(registry).parametersSchema as ToolParameters.JsonSchema).schema
        assertTrue("skill_name" in schema)
    }

    @Test
    fun `execute loads skill content`() = runTest {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        val tool = LoadSkillTool(registry)
        val result = tool.execute(
            buildJsonObject { put("skill_name", kotlinx.serialization.json.JsonPrimitive("weather")) },
            ToolContext(),
        )
        assertEquals("## weather body", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `execute returns error for unknown skill`() = runTest {
        val registry = SkillRegistry()
        val tool = LoadSkillTool(registry)
        val result = tool.execute(
            buildJsonObject { put("skill_name", kotlinx.serialization.json.JsonPrimitive("unknown")) },
            ToolContext(),
        )
        assertTrue(result.isError)
        assertTrue("Skill not found" in result.content)
    }

    @Test
    fun `execute returns error for missing skill_name`() = runTest {
        val registry = SkillRegistry()
        val tool = LoadSkillTool(registry)
        val result = tool.execute(buildJsonObject { }, ToolContext())
        assertTrue(result.isError)
        assertTrue("Missing skill_name" in result.content)
    }
}