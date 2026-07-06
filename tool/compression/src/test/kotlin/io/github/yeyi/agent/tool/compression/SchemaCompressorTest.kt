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
import kotlin.test.assertFalse
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
    fun compressNestedObjectRecursesFields() {
        // 内层对象类型应保留 fields 结构(以前会压扁成 ObjectType())
        val result = compressor.compress("create_user", """
            {
                "type": "object",
                "properties": {
                    "user": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer"}
                        },
                        "required": ["name"]
                    }
                },
                "required": ["user"]
            }
        """.trimIndent())

        val userParam = result.signature.params.find { it.name == "user" }!!
        assertTrue(userParam.type is ParamType.ObjectType)
        val userType = userParam.type as ParamType.ObjectType
        assertEquals(2, userType.fields.size)
        assertEquals("name", userType.fields[0].name)
        assertTrue(userType.fields[0].required)
        assertTrue(userType.fields[0].type is ParamType.StringType)
        assertEquals("age", userType.fields[1].name)
        assertFalse(userType.fields[1].required)
    }

    @Test
    fun compressArrayOfObjectsKeepsElementSchema() {
        // 数组元素的 schema 应当被保留到 ObjectType(isArray=true).fields
        val result = compressor.compress("create_users", """
            {
                "type": "object",
                "properties": {
                    "users": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "name": {"type": "string"},
                                "role": {"type": "string", "enum": ["admin", "user"]}
                            },
                            "required": ["name"]
                        }
                    }
                },
                "required": ["users"]
            }
        """.trimIndent())

        val usersParam = result.signature.params.find { it.name == "users" }!!
        assertTrue(usersParam.type is ParamType.ObjectType)
        val usersType = usersParam.type as ParamType.ObjectType
        assertTrue(usersType.isArray)
        assertEquals(2, usersType.fields.size)
        val roleField = usersType.fields.find { it.name == "role" }!!
        assertTrue(roleField.type is ParamType.EnumType)
        assertEquals(listOf("admin", "user"), (roleField.type as ParamType.EnumType).values)
    }

    @Test
    fun formatSignatureRendersNestedStructure() {
        // 签名应把内层结构渲染为 { name: type, ... }(数组对象则是 [{...}])
        val result = compressor.compress("create_orders", """
            {
                "type": "object",
                "properties": {
                    "orders": {
                        "type": "object",
                        "properties": {
                            "order_id": {"type": "string"},
                            "items": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "sku": {"type": "string"},
                                        "qty": {"type": "integer"}
                                    },
                                    "required": ["sku", "qty"]
                                }
                            }
                        },
                        "required": ["order_id", "items"]
                    }
                },
                "required": ["orders"]
            }
        """.trimIndent())

        val schemaJson = kotlinx.serialization.json.Json.parseToJsonElement(result.compressedSchema)
        val description = schemaJson.jsonObject["properties"]?.jsonObject
            ?.get("execution")?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""

        assertTrue(description.contains("orders:"), "orders missing: $description")
        assertTrue(description.contains("order_id:"), "order_id missing: $description")
        assertTrue(description.contains("items: [{"), "items array-of-object missing: $description")
        assertTrue(description.contains("sku:"), "sku missing: $description")
        assertTrue(description.contains("qty:"), "qty missing: $description")
        assertTrue(description.contains("qty: number"), "qty should be number: $description")
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

    @Test
    fun oneOfWithEnumDiscriminatorTreatedAsConst() {
        // 单值 enum 等价 const,应当作 discriminator
        val result = compressor.compress("notif", """
            {
                "oneOf": [
                    {
                        "properties": {
                            "kind": {"type": "string", "enum": ["email"]},
                            "to": {"type": "string"}
                        },
                        "required": ["kind", "to"]
                    },
                    {
                        "properties": {
                            "kind": {"type": "string", "enum": ["sms"]},
                            "phone": {"type": "string"}
                        },
                        "required": ["kind", "phone"]
                    }
                ]
            }
        """.trimIndent())

        assertTrue(result.signature.isOneOf)
        assertEquals(2, result.signature.branches.size)
        assertEquals("kind=email", result.signature.branches[0].condition)
        assertEquals("kind=sms", result.signature.branches[1].condition)
        // 判别字段 kind 不应在 params 里
        assertTrue(result.signature.branches[0].params.none { it.name == "kind" })
        assertTrue(result.signature.branches[1].params.none { it.name == "kind" })
    }

    @Test
    fun oneOfWithNoDiscriminatorBranchBecomesCatchAll() {
        // 没有 const/单值 enum 的分支(纯 catch-all)应保留,condition 为空
        val result = compressor.compress("event", """
            {
                "oneOf": [
                    {
                        "properties": {
                            "type": {"const": "click"},
                            "x": {"type": "number"}
                        },
                        "required": ["type"]
                    },
                    {
                        "properties": {
                            "payload": {"type": "string"}
                        }
                    }
                ]
            }
        """.trimIndent())

        assertEquals(2, result.signature.branches.size)
        assertEquals("type=click", result.signature.branches[0].condition)
        assertEquals("", result.signature.branches[1].condition) // catch-all
        assertEquals(1, result.signature.branches[1].params.size)
    }

    @Test
    fun oneOfNestedInsideObject() {
        // oneOf 嵌在 object 的字段里(不是顶层)
        val result = compressor.compress("send", """
            {
                "type": "object",
                "properties": {
                    "channel": {
                        "oneOf": [
                            {
                                "properties": {
                                    "type": {"const": "email"},
                                    "to": {"type": "string"}
                                },
                                "required": ["type", "to"]
                            },
                            {
                                "properties": {
                                    "type": {"const": "webhook"},
                                    "url": {"type": "string"}
                                },
                                "required": ["type", "url"]
                            }
                        ]
                    }
                },
                "required": ["channel"]
            }
        """.trimIndent())

        val channelParam = result.signature.params.find { it.name == "channel" }!!
        assertTrue(channelParam.type is ParamType.OneOfType)
        val oneOf = channelParam.type as ParamType.OneOfType
        assertEquals(2, oneOf.branches.size)
        assertEquals("type=email", oneOf.branches[0].condition)
        assertEquals("type=webhook", oneOf.branches[1].condition)
    }

    @Test
    fun oneOfAsArrayElementType() {
        // 数组元素是 oneOf:items.oneOf + type=array
        val result = compressor.compress("log", """
            {
                "type": "object",
                "properties": {
                    "events": {
                        "type": "array",
                        "items": {
                            "oneOf": [
                                {
                                    "properties": {
                                        "type": {"const": "click"},
                                        "x": {"type": "number"},
                                        "y": {"type": "number"}
                                    },
                                    "required": ["type", "x"]
                                },
                                {
                                    "properties": {
                                        "type": {"const": "view"},
                                        "page": {"type": "string"}
                                    },
                                    "required": ["type", "page"]
                                }
                            ]
                        }
                    }
                },
                "required": ["events"]
            }
        """.trimIndent())

        val eventsParam = result.signature.params.find { it.name == "events" }!!
        assertTrue(eventsParam.type is ParamType.OneOfType)
        val oneOf = eventsParam.type as ParamType.OneOfType
        assertTrue(oneOf.isArray, "array-of-oneOf should be flagged: $oneOf")
        assertEquals(2, oneOf.branches.size)
    }

    @Test
    fun anyOfFallsThroughToOneOf() {
        // anyOf 在我们这套压缩语义里和 oneOf 等价(都表达多分支可能)
        val result = compressor.compress("payload", """
            {
                "anyOf": [
                    {
                        "properties": {
                            "kind": {"const": "a"},
                            "a_val": {"type": "string"}
                        },
                        "required": ["kind"]
                    },
                    {
                        "properties": {
                            "kind": {"const": "b"},
                            "b_val": {"type": "number"}
                        },
                        "required": ["kind"]
                    }
                ]
            }
        """.trimIndent())

        assertTrue(result.signature.isOneOf)
        assertEquals(2, result.signature.branches.size)
        assertEquals("kind=a", result.signature.branches[0].condition)
        assertEquals("kind=b", result.signature.branches[1].condition)
    }

    @Test
    fun allOfMergesProperties() {
        // allOf 把多个子 schema 合并(简化处理:同名字段取第一个,required 取并集)
        val result = compressor.compress("merged", """
            {
                "allOf": [
                    {
                        "properties": {
                            "name": {"type": "string"},
                            "age": {"type": "integer"}
                        },
                        "required": ["name"]
                    },
                    {
                        "properties": {
                            "email": {"type": "string"}
                        },
                        "required": ["email"]
                    }
                ]
            }
        """.trimIndent())

        // allOf 合成一个 object
        assertEquals(3, result.signature.params.size)
        val nameParam = result.signature.params.find { it.name == "name" }!!
        assertTrue(nameParam.required)
        val emailParam = result.signature.params.find { it.name == "email" }!!
        assertTrue(emailParam.required)
        val ageParam = result.signature.params.find { it.name == "age" }!!
        assertFalse(ageParam.required)
    }

    @Test
    fun allOfInsideFieldMergesProperties() {
        // 字段层 allOf:profile 字段是 allOf,合成一个带 fields 的 ObjectType
        val result = compressor.compress("register", """
            {
                "type": "object",
                "properties": {
                    "profile": {
                        "allOf": [
                            {
                                "properties": {
                                    "name": {"type": "string"},
                                    "age": {"type": "integer"}
                                },
                                "required": ["name"]
                            },
                            {
                                "properties": {
                                    "email": {"type": "string"}
                                },
                                "required": ["email"]
                            }
                        ]
                    }
                },
                "required": ["profile"]
            }
        """.trimIndent())

        val profileParam = result.signature.params.find { it.name == "profile" }!!
        assertTrue(profileParam.type is ParamType.ObjectType)
        val profileType = profileParam.type as ParamType.ObjectType
        assertEquals(3, profileType.fields.size)
        assertTrue(profileType.fields.find { it.name == "name" }!!.required)
        assertTrue(profileType.fields.find { it.name == "email" }!!.required)
        assertFalse(profileType.fields.find { it.name == "age" }!!.required)
    }

    @Test
    fun oneOfFormatRendersCatchAllWithAsterisk() {
        // catch-all 分支(空 condition)在签名里用 * 前缀标识,跟普通 condition=... 区分开
        val result = compressor.compress("event", """
            {
                "oneOf": [
                    {
                        "properties": {
                            "type": {"const": "click"},
                            "x": {"type": "number"}
                        },
                        "required": ["type"]
                    },
                    {
                        "properties": {
                            "extra": {"type": "string"}
                        }
                    }
                ]
            }
        """.trimIndent())

        val desc = result.compressedSchema
        assertTrue("type=click" in desc, "normal branch missing: $desc")
        assertTrue("x?: number" in desc, "normal branch params missing: $desc")
        assertTrue("; * extra?: string" in desc, "catch-all branch missing or not marked: $desc")
    }

    @Test
    fun oneOfInsideObjectFieldRendersInline() {
        // oneOf 作为字段类型时,签名里应渲染 {a=val, ...} | {b=val, ...}
        val result = compressor.compress("send", """
            {
                "type": "object",
                "properties": {
                    "channel": {
                        "oneOf": [
                            {
                                "properties": {
                                    "type": {"const": "email"},
                                    "to": {"type": "string"}
                                },
                                "required": ["type", "to"]
                            },
                            {
                                "properties": {
                                    "type": {"const": "webhook"},
                                    "url": {"type": "string"}
                                },
                                "required": ["type", "url"]
                            }
                        ]
                    }
                },
                "required": ["channel"]
            }
        """.trimIndent())

        val desc = result.compressedSchema
        assertTrue("channel:" in desc, "channel field missing: $desc")
        assertTrue("{type=email" in desc, "email branch missing: $desc")
        assertTrue("{type=webhook" in desc, "webhook branch missing: $desc")
    }
}
