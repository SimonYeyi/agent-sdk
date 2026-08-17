package io.github.yeyi.agent

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDefinitionTest {

    private class StubTool(
        override val name: String,
        override val description: String,
        override val parametersSchema: ToolParameters,
    ) : Tool {
        override suspend fun execute(
            arguments: kotlinx.serialization.json.JsonElement,
            context: ToolContext,
        ): ToolExecutionResult = ToolExecutionResult.success("stub")
    }

    @Test
    fun `toDefinition passes name and description`() {
        val tool = StubTool("get_weather", "获取天气", ToolParameters.Empty)
        val def = tool.toDefinition()
        assertEquals("get_weather", def.name)
        assertEquals("获取天气", def.description)
    }

    @Test
    fun `toDefinition returns a valid JsonObject for Empty parametersSchema`() {
        val tool = StubTool("noop", "no params", ToolParameters.Empty)
        val params = tool.toDefinition().parametersSchema
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), params["properties"]!!.jsonObject)
    }

    @Test
    fun `toDefinition parses JsonSchema into JsonObject`() {
        val schema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        val tool = StubTool("get_weather", "获取天气", ToolParameters.JsonSchema(schema))
        val params = tool.toDefinition().parametersSchema
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("city"), params["properties"]!!.jsonObject.keys)
    }
}