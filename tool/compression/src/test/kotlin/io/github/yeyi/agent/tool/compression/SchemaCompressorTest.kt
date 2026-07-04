package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class SchemaCompressorTest {

    private val compressor = DefaultSchemaCompressor()

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

    @Test
    fun compressMusicControlSchema() {
        // 音乐控制工具：action 决定需要哪些额外参数
        val result = compressor.compress("music_control", """
            {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["play", "pause", "stop", "prev", "next", "volume", "mode"],
                        "description": "操作类型"
                    },
                    "song": {
                        "type": "string",
                        "description": "播放歌曲时必填"
                    },
                    "artist": {
                        "type": "string",
                        "description": "播放歌曲时可选"
                    },
                    "volume": {
                        "type": "integer",
                        "description": "调节音量时必填，0-100"
                    },
                    "mode": {
                        "type": "string",
                        "enum": ["normal", "repeat", "shuffle"],
                        "description": "切换播放模式时必填"
                    }
                },
                "required": ["action"]
            }
        """.trimIndent())

        assertEquals("music_control", result.signature.name)
        assertEquals(5, result.signature.params.size)

        // 验证 action 是枚举类型
        val actionParam = result.signature.params[0]
        assertTrue(actionParam.type is ParamType.EnumType)
        assertEquals(listOf("play", "pause", "stop", "prev", "next", "volume", "mode"), (actionParam.type as ParamType.EnumType).values)
        assertEquals("操作类型", actionParam.description)

        // 验证各字段描述
        val songParam = result.signature.params.find { it.name == "song" }
        assertEquals("播放歌曲时必填", songParam?.description)

        val volumeParam = result.signature.params.find { it.name == "volume" }
        assertEquals("调节音量时必填，0-100", volumeParam?.description)

        val modeParam = result.signature.params.find { it.name == "mode" }
        assertTrue(modeParam?.type is ParamType.EnumType)
        assertEquals(listOf("normal", "repeat", "shuffle"), (modeParam?.type as ParamType.EnumType).values)
        assertEquals("切换播放模式时必填", modeParam?.description)
    }

    @Test
    fun compressedSchemaWithConditionalFields() {
        // 验证压缩后的 schema 包含所有字段的描述信息
        val result = compressor.compress("music_control", """
            {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["play", "pause", "volume"]
                    },
                    "song": {
                        "type": "string",
                        "description": "播放歌曲时必填"
                    },
                    "volume": {
                        "type": "integer",
                        "description": "调节音量时必填"
                    }
                },
                "required": ["action"]
            }
        """.trimIndent())

        // 压缩后的 schema 应包含完整的描述信息，帮助模型理解条件约束
        val schemaJson = kotlinx.serialization.json.Json.parseToJsonElement(result.compressedSchema)
        val execution = schemaJson.jsonObject["properties"]?.jsonObject?.get("execution")?.jsonObject
        val description = execution?.get("description")?.jsonPrimitive?.content ?: ""

        assertTrue(description.contains("action: enum(play, pause, volume)"), "action enum missing: $description")
        assertTrue(description.contains("song?: string"), "song string missing: $description")
        assertTrue(description.contains("播放歌曲时必填"), "song desc missing: $description")
        assertTrue(description.contains("volume?: number"), "volume number missing: $description")
        assertTrue(description.contains("调节音量时必填"), "volume desc missing: $description")
    }

    @Test
    fun compressOneOfSchema() {
        // oneOf 条件参数 schema
        val result = compressor.compress("music_control", """
            {
                "oneOf": [
                    {
                        "properties": {
                            "action": {"const": "play"},
                            "song": {"type": "string", "description": "歌曲名"},
                            "artist": {"type": "string", "description": "歌手"}
                        },
                        "required": ["action", "song"]
                    },
                    {
                        "properties": {
                            "action": {"const": "pause"}
                        },
                        "required": ["action"]
                    },
                    {
                        "properties": {
                            "action": {"const": "volume"},
                            "volume": {"type": "integer", "description": "音量0-100"}
                        },
                        "required": ["action", "volume"]
                    }
                ]
            }
        """.trimIndent())

        assertEquals("music_control", result.signature.name)
        assertTrue(result.signature.isOneOf)
        assertEquals(3, result.signature.branches.size)

        // 验证第一个分支 (play)
        val playBranch = result.signature.branches[0]
        assertEquals("action=play", playBranch.condition)
        assertEquals(2, playBranch.params.size)
        assertEquals("song", playBranch.params[0].name)
        assertEquals("artist", playBranch.params[1].name)

        // 验证第二个分支 (pause) - 空分支
        val pauseBranch = result.signature.branches[1]
        assertEquals("action=pause", pauseBranch.condition)
        assertEquals(0, pauseBranch.params.size)

        // 验证第三个分支 (volume)
        val volumeBranch = result.signature.branches[2]
        assertEquals("action=volume", volumeBranch.condition)
        assertEquals(1, volumeBranch.params.size)
        assertEquals("volume", volumeBranch.params[0].name)
    }

    @Test
    fun compressedSchemaWithOneOf() {
        val result = compressor.compress("music_control", """
            {
                "oneOf": [
                    {
                        "properties": {
                            "action": {"const": "play"},
                            "song": {"type": "string"}
                        },
                        "required": ["action", "song"]
                    },
                    {
                        "properties": {
                            "action": {"const": "pause"}
                        },
                        "required": ["action"]
                    }
                ]
            }
        """.trimIndent())

        val schemaJson = kotlinx.serialization.json.Json.parseToJsonElement(result.compressedSchema)
        val execution = schemaJson.jsonObject["properties"]?.jsonObject?.get("execution")?.jsonObject
        val description = execution?.get("description")?.jsonPrimitive?.content ?: ""

        // 验证 oneOf 格式：action=play, song: string; action=pause
        assertTrue(description.contains("action=play,"), "play branch missing: $description")
        assertTrue(description.contains("song: string"), "song param missing: $description")
        assertTrue(description.contains("action=pause"), "pause branch missing: $description")
        assertTrue(description.contains(";"), "branches should be separated by ;")
    }
}
