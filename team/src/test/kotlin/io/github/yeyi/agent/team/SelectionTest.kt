package io.github.yeyi.agent.team

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectionTest {

    @Test
    fun `Skill selection has correct type`() {
        val sel = Selection.Skill("web_search")
        assertEquals("skill", sel.type)
    }

    @Test
    fun `Toolset selection has correct type`() {
        val sel = Selection.Toolset("weather")
        assertEquals("toolset", sel.type)
    }

    @Test
    fun `Tool selection has correct type`() {
        val sel = Selection.Tool("get_time")
        assertEquals("tool", sel.type)
    }

    @Test
    fun `Subagent selection has correct type`() {
        val sel = Selection.Subagent("reviewer")
        assertEquals("subagent", sel.type)
    }

    @Test
    fun `FACTORIES maps all types`() {
        assertEquals(4, Selection.FACTORIES.size)
        assertNotNull(Selection.FACTORIES["skill"])
        assertNotNull(Selection.FACTORIES["toolset"])
        assertNotNull(Selection.FACTORIES["tool"])
        assertNotNull(Selection.FACTORIES["subagent"])
    }

    @Test
    fun `FACTORIES creates correct Selection subtypes`() {
        assertTrue(Selection.FACTORIES["skill"]!!("web_search") is Selection.Skill)
        assertTrue(Selection.FACTORIES["toolset"]!!("weather") is Selection.Toolset)
        assertTrue(Selection.FACTORIES["tool"]!!("get_time") is Selection.Tool)
        assertTrue(Selection.FACTORIES["subagent"]!!("reviewer") is Selection.Subagent)
    }

    @Test
    fun `unknown type returns null from FACTORIES`() {
        assertEquals(null, Selection.FACTORIES["unknown"])
    }
}
