package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LazyToolRegistryTest {

    private class FixedTool(
        override val name: String,
        override val description: String = "test tool",
        private val schema: ToolParameters = ToolParameters.Empty,
    ) : Tool {
        override val parametersSchema: ToolParameters = schema
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    private fun emptyContext(): LazyToolContext = LazyToolContext()

    @Test
    fun `registry capabilityType is lazy_tool`() {
        val r = LazyToolRegistry()
        assertEquals(LazyTool.CAPABILITY_TYPE, r.capabilityType)
        assertEquals("lazy_tool", r.capabilityType)
    }

    @Test
    fun `register adds lazy tool to registry`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        val names = registry.all().map { it.name }.toSet()
        assertTrue("weather" in names)
    }

    @Test
    fun `register multiple lazy tools`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("a", "d1")))
        registry.register(LazyTool(FixedTool("b", "d2")))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("a", "b"), names)
    }

    @Test
    fun `duplicate lazy tool name throws`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("dup", "d")))
        assertFailsWith<IllegalArgumentException> {
            registry.register(LazyTool(FixedTool("dup", "d2")))
        }
    }

    @Test
    fun `all returns all registered lazy tools`() {
        val registry = LazyToolRegistry()
        val t1 = LazyTool(FixedTool("weather", "d"))
        val t2 = LazyTool(FixedTool("news", "d"))
        registry.register(t1)
        registry.register(t2)
        val all = registry.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "weather" })
        assertTrue(all.any { it.name == "news" })
    }

    @Test
    fun `lazy tool exposes wrapped tool name and description`() {
        val registry = LazyToolRegistry()
        val tool = FixedTool("weather", "查询天气")
        registry.register(LazyTool(tool))
        val lazyTool = registry.all().find { it.name == "weather" }!!
        assertEquals("weather", lazyTool.name)
        assertEquals("查询天气", lazyTool.description)
    }

    @Test
    fun `lazy tool activate returns tool schema`() = runTest {
        val schema = ToolParameters.JsonSchema("""{"type":"object","properties":{"city":{"type":"string"}}}""")
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", schema = schema)))
        val lazyTool = registry.all().find { it.name == "weather" }!!
        val result = lazyTool.activate(null, emptyContext())
        assertTrue(result.contains("weather"))
        assertTrue(result.contains("schema"))
    }

    @Test
    fun `lazy tool wrapped tool is accessible`() {
        val tool = FixedTool("weather", "查询天气")
        val registry = LazyToolRegistry()
        registry.register(LazyTool(tool))
        val lazyTool = registry.all().find { it.name == "weather" }!!
        assertEquals(tool, lazyTool.tool)
    }
}
