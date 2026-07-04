package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun createTool(name: String, schema: String): Tool = object : Tool {
    override val name = name
    override val description = "test"
    override val parametersSchema = ToolParameters.JsonSchema(schema)
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        return ToolExecutionResult.success("")
    }
}

private fun createEmptyTool(name: String): Tool = object : Tool {
    override val name = name
    override val description = "test"
    override val parametersSchema = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        return ToolExecutionResult.success("")
    }
}

class SignatureCompressorTest {

    private val compressor = DefaultSignatureCompressor()

    @Test
    fun compressSimpleSchema() {
        val result = compressor.compress("send_email", """
            {
                "type": "object",
                "properties": {
                    "to": { "type": "string" },
                    "subject": { "type": "string" }
                },
                "required": ["to"]
            }
        """.trimIndent())

        assertEquals("send_email", result.signature.name)
        assertEquals(2, result.signature.params.size)
    }

    @Test
    fun compressSchemaWithAllTypes() {
        val result = compressor.compress("test_tool", """
            {
                "type": "object",
                "properties": {
                    "name": { "type": "string" },
                    "age": { "type": "number" },
                    "active": { "type": "boolean" },
                    "tags": { "type": "array", "items": { "type": "string" } },
                    "config": { "type": "object" }
                },
                "required": ["name", "age", "active", "tags", "config"]
            }
        """.trimIndent())

        assertEquals(5, result.signature.params.size)
        assertTrue(result.signature.params[0].type is ParamType.StringType)
        assertTrue(result.signature.params[1].type is ParamType.NumberType)
        assertTrue(result.signature.params[2].type is ParamType.BooleanType)
        assertTrue(result.signature.params[3].type is ParamType.StringType)
        assertTrue(result.signature.params[4].type is ParamType.ObjectType)
    }

    @Test
    fun compressSchemaWithEnum() {
        val result = compressor.compress("update_status", """
            {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["todo", "in_progress", "done"]
                    }
                },
                "required": ["status"]
            }
        """.trimIndent())

        assertTrue(result.signature.params[0].type is ParamType.EnumType)
        val enumType = result.signature.params[0].type as ParamType.EnumType
        assertEquals(listOf("todo", "in_progress", "done"), enumType.values)
    }

    @Test
    fun compressToolWithDescription() {
        val result = compressor.compress("send_email", """
            {
                "type": "object",
                "properties": {
                    "to": {
                        "type": "string",
                        "description": "收件人邮箱"
                    }
                },
                "required": ["to"]
            }
        """.trimIndent())

        assertEquals("收件人邮箱", result.signature.params[0].description)
    }

    @Test
    fun compressEmptySchema() {
        val result = compressor.compress("noop", "{}")

        assertEquals("noop", result.signature.name)
        assertTrue(result.signature.params.isEmpty())
    }

    @Test
    fun compressedSchemaContainsExecution() {
        val result = compressor.compress("send_email", """
            {
                "type": "object",
                "properties": {
                    "to": { "type": "string" }
                },
                "required": ["to"]
            }
        """.trimIndent())

        assertTrue(result.compressedSchema.contains("execution"))
        assertTrue(result.compressedSchema.contains("send_email(to: string)"))
    }
}
