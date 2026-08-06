package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaCompressorTest {

    private val compressor = DefaultSchemaCompressor()

    // region 公共 Fixture —— 两份 schema 覆盖全部测试场景，各用例按需断言其中某个方面

    /**
     * 顶层 object：覆盖基础类型、枚举、嵌套对象、数组对象、数组 oneOf、oneOf 字段、allOf 字段、各层 description。
     */
    private val richObjectSchema = """
        {
            "type": "object",
            "properties": {
                "name": { "type": "string", "description": "名称" },
                "age": { "type": "integer", "description": "年龄" },
                "score": { "type": "number" },
                "active": { "type": "boolean" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "status": { "type": "string", "enum": ["todo", "done"], "description": "状态" },
                "config": {
                    "type": "object",
                    "description": "配置对象",
                    "properties": {
                        "host": { "type": "string", "description": "主机地址" },
                        "port": { "type": "integer", "description": "端口号" }
                    },
                    "required": ["host"]
                },
                "users": {
                    "type": "array",
                    "description": "用户列表",
                    "items": {
                        "type": "object",
                        "description": "单个用户",
                        "properties": {
                            "name": { "type": "string" },
                            "role": { "type": "string", "enum": ["admin", "user"] }
                        },
                        "required": ["name"]
                    }
                },
                "orders": {
                    "type": "object",
                    "properties": {
                        "order_id": { "type": "string" },
                        "items": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "sku": { "type": "string" },
                                    "qty": { "type": "integer" }
                                },
                                "required": ["sku", "qty"]
                            }
                        }
                    },
                    "required": ["order_id", "items"]
                },
                "channel": {
                    "oneOf": [
                        {
                            "description": "邮件发送",
                            "properties": {
                                "type": { "const": "email" },
                                "to": { "type": "string", "description": "收件人邮箱" },
                                "subject": { "type": "string", "description": "邮件主题" }
                            },
                            "required": ["type", "to"]
                        },
                        {
                            "description": "短信发送",
                            "properties": {
                                "type": { "const": "sms" },
                                "phone": { "type": "string" }
                            },
                            "required": ["type", "phone"]
                        },
                        {
                            "properties": {
                                "extra": { "type": "string" }
                            }
                        }
                    ]
                },
                "events": {
                    "type": "array",
                    "items": {
                        "oneOf": [
                            {
                                "properties": {
                                    "type": { "const": "click" },
                                    "x": { "type": "number" },
                                    "y": { "type": "number" }
                                },
                                "required": ["type", "x"]
                            },
                            {
                                "properties": {
                                    "type": { "const": "view" },
                                    "page": { "type": "string" }
                                },
                                "required": ["type", "page"]
                            }
                        ]
                    }
                },
                "profile": {
                    "allOf": [
                        {
                            "properties": {
                                "name": { "type": "string" },
                                "age": { "type": "integer" }
                            },
                            "required": ["name"]
                        },
                        {
                            "properties": {
                                "email": { "type": "string" }
                            },
                            "required": ["email"]
                        }
                    ]
                },
                "server": {
                    "type": "object",
                    "description": "服务器设置",
                    "properties": {
                        "host": { "type": "string" }
                    }
                }
            },
            "required": ["name", "age", "active", "status", "config", "users", "orders", "channel", "events", "profile", "server"]
        }
    """.trimIndent()

    /**
     * 顶层 oneOf：覆盖 const 判别、单值 enum 判别、catch-all 分支、空分支、分支描述、参数描述。
     */
    private val topOneOfSchema = """
        {
            "oneOf": [
                {
                    "description": "播放一首歌曲",
                    "properties": {
                        "action": { "const": "play" },
                        "song": { "type": "string" },
                        "artist": { "type": "string" }
                    },
                    "required": ["action", "song"]
                },
                {
                    "description": "暂停",
                    "properties": {
                        "action": { "const": "pause" }
                    },
                    "required": ["action"]
                },
                {
                    "description": "调整音量",
                    "properties": {
                        "action": { "type": "string", "enum": ["volume"] },
                        "volume": { "type": "integer", "description": "音量0-100" }
                    },
                    "required": ["action", "volume"]
                },
                {
                    "properties": {
                        "extra": { "type": "string" }
                    }
                }
            ]
        }
    """.trimIndent()

    // endregion

    // region 基础类型与字段

    @Test
    fun compressSimpleSchema() {
        val result = compressor.compress("test_tool", richObjectSchema)
        assertEquals("test_tool", result.signature.name)
        assertTrue(result.signature.params.isNotEmpty())
        val name = result.signature.params.find { it.name == "name" }!!
        assertTrue(name.type is ParamType.StringType)
    }

    @Test
    fun compressSchemaWithAllTypes() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val params = result.signature.params
        assertTrue(params.find { it.name == "name" }!!.type is ParamType.StringType)
        assertTrue(params.find { it.name == "age" }!!.type is ParamType.NumberType)
        assertTrue(params.find { it.name == "score" }!!.type is ParamType.NumberType)
        assertTrue(params.find { it.name == "active" }!!.type is ParamType.BooleanType)
        val tags = params.find { it.name == "tags" }!!
        assertTrue(tags.type is ParamType.StringType)
        assertTrue((tags.type as ParamType.StringType).isArray)
        assertTrue(params.find { it.name == "config" }!!.type is ParamType.ObjectType)
    }

    @Test
    fun compressSchemaWithEnum() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val status = result.signature.params.find { it.name == "status" }!!
        assertTrue(status.type is ParamType.EnumType)
        assertEquals(listOf("todo", "done"), (status.type as ParamType.EnumType).values)
        // 枚举字段也可携带 description（原 compressMusicControlSchema 覆盖点）
        assertEquals("状态", status.description)
        // 一个 object 可同时存在多个枚举字段（users.role 也是枚举，见 compressArrayOfObjectsKeepsElementSchema）
    }

    @Test
    fun compressToolWithDescription() {
        val result = compressor.compress("test_tool", richObjectSchema)
        assertEquals("名称", result.signature.params.find { it.name == "name" }!!.description)
        // 验证参数总数（原 compressMusicControlSchema / compressSchemaWithAllTypes 覆盖点）
        assertEquals(13, result.signature.params.size)
    }

    @Test
    fun compressEmptySchema() {
        val result = compressor.compress("noop", "{}")
        assertEquals("noop", result.signature.name)
        assertTrue(result.signature.params.isEmpty())
    }

    @Test
    fun compressedSchemaContainsExecution() {
        val result = compressor.compress("test_tool", richObjectSchema)
        assertTrue(result.compressedSchema.contains("execution"))
        // 完整签名格式：name(type) + 顶层字段渲染（原 compressedSchemaContainsExecution 覆盖点）
        val desc = extractExecutionDescription(result.compressedSchema)
        assertTrue(desc.startsWith("test_tool("), "signature prefix missing: $desc")
        assertTrue("name: string" in desc, "required string field missing: $desc")
    }

    @Test
    fun topLevelFieldsRenderWithTypeOptionalAndDescription() {
        // 顶层简单字段的渲染格式：field?: type | "desc"（原 compressedSchemaWithConditionalFields 覆盖点）
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = extractExecutionDescription(result.compressedSchema)
        // 必填 + 描述
        assertTrue("name: string | \"名称\"" in desc, "required+desc render missing: $desc")
        // 可选 + 无描述
        assertTrue("score?: number" in desc, "optional no-desc render missing: $desc")
        // 必填 + 枚举 + 描述（无 ? 后缀）
        assertTrue("status: enum(todo, done) | \"状态\"" in desc, "required+enum+desc render missing: $desc")
        // 可选 + 数组
        assertTrue("tags?: string[]" in desc, "optional array render missing: $desc")
    }

    // endregion

    // region 嵌套对象与数组

    @Test
    fun compressNestedObjectRecursesFields() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val config = result.signature.params.find { it.name == "config" }!!
        assertTrue(config.type is ParamType.ObjectType)
        val configType = config.type as ParamType.ObjectType
        assertEquals(2, configType.fields.size)
        assertEquals("host", configType.fields[0].name)
        assertTrue(configType.fields[0].required)
        assertEquals("port", configType.fields[1].name)
        assertFalse(configType.fields[1].required)
    }

    @Test
    fun compressArrayOfObjectsKeepsElementSchema() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val users = result.signature.params.find { it.name == "users" }!!
        assertTrue(users.type is ParamType.ObjectType)
        val usersType = users.type as ParamType.ObjectType
        assertTrue(usersType.isArray)
        assertEquals(2, usersType.fields.size)
        val role = usersType.fields.find { it.name == "role" }!!
        assertTrue(role.type is ParamType.EnumType)
        assertEquals(listOf("admin", "user"), (role.type as ParamType.EnumType).values)
    }

    @Test
    fun formatSignatureRendersNestedStructure() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = extractExecutionDescription(result.compressedSchema)
        assertTrue("orders:" in desc, "orders missing: $desc")
        assertTrue("order_id:" in desc, "order_id missing: $desc")
        assertTrue("items: [{" in desc, "items array-of-object missing: $desc")
        assertTrue("sku:" in desc, "sku missing: $desc")
        assertTrue("qty:" in desc, "qty missing: $desc")
        assertTrue("qty: number" in desc, "qty should be number: $desc")
    }

    // endregion

    // region oneOf 字段（嵌套在 object 里）

    @Test
    fun oneOfNestedInsideObject() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val channel = result.signature.params.find { it.name == "channel" }!!
        assertTrue(channel.type is ParamType.OneOfType)
        val oneOf = channel.type as ParamType.OneOfType
        assertEquals(3, oneOf.branches.size)
        assertEquals("type=email", oneOf.branches[0].condition)
        assertEquals("type=sms", oneOf.branches[1].condition)
        assertEquals("", oneOf.branches[2].condition) // catch-all
    }

    @Test
    fun oneOfAsArrayElementType() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val events = result.signature.params.find { it.name == "events" }!!
        assertTrue(events.type is ParamType.OneOfType)
        val oneOf = events.type as ParamType.OneOfType
        assertTrue(oneOf.isArray, "array-of-oneOf should be flagged: $oneOf")
        assertEquals(2, oneOf.branches.size)
    }

    @Test
    fun oneOfInsideObjectFieldRendersInline() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertTrue("channel:" in desc, "channel field missing: $desc")
        assertTrue("{type=email" in desc, "email branch missing: $desc")
        assertTrue("{type=sms" in desc, "sms branch missing: $desc")
    }

    @Test
    fun nestedOneOfBranchParamsKeepTheirDescriptions() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertTrue("收件人邮箱" in desc, "email branch to desc missing: $desc")
        assertTrue("邮件主题" in desc, "email branch subject desc missing: $desc")
    }

    @Test
    fun oneOfBranchDescriptionPreserved() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertTrue("邮件发送" in desc, "email branch desc missing: $desc")
        assertTrue("短信发送" in desc, "sms branch desc missing: $desc")
    }

    // endregion

    // region allOf 字段

    @Test
    fun allOfInsideFieldMergesProperties() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val profile = result.signature.params.find { it.name == "profile" }!!
        assertTrue(profile.type is ParamType.ObjectType)
        val profileType = profile.type as ParamType.ObjectType
        assertEquals(3, profileType.fields.size)
        assertTrue(profileType.fields.find { it.name == "name" }!!.required)
        assertTrue(profileType.fields.find { it.name == "email" }!!.required)
        assertFalse(profileType.fields.find { it.name == "age" }!!.required)
    }

    // endregion

    // region description 渲染

    @Test
    fun nestedObjectFieldsKeepTheirDescriptions() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertTrue("主机地址" in desc, "host description missing in nested object: $desc")
        assertTrue("端口号" in desc, "port description missing in nested object: $desc")
    }

    @Test
    fun objectWrapperDescriptionRendered() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertEquals(1, desc.split("配置对象").size - 1, "wrapper description duplicated: $desc")
        assertTrue("host: string" in desc, "host field missing: $desc")
        assertTrue("port?: number" in desc, "port field missing: $desc")
    }

    @Test
    fun descriptionNotDuplicatedBetweenParamAndType() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertEquals(1, desc.split("服务器设置").size - 1, "description should appear exactly once: $desc")
    }

    @Test
    fun arrayOfObjectKeepsOuterArrayDescription() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertTrue("用户列表" in desc, "outer array description lost: $desc")
        assertTrue("role" in desc, "items field missing: $desc")
    }

    @Test
    fun arrayOfObjectKeepsBothDescriptions() {
        val result = compressor.compress("test_tool", richObjectSchema)
        val desc = result.compressedSchema
        assertEquals(1, desc.split("用户列表").size - 1, "outer description missing or duplicated: $desc")
        assertEquals(1, desc.split("单个用户").size - 1, "items description missing or duplicated: $desc")
    }

    // endregion

    // region 顶层 oneOf

    @Test
    fun compressOneOfSchema() {
        val result = compressor.compress("music_control", topOneOfSchema)
        assertEquals("music_control", result.signature.name)
        assertTrue(result.signature.isOneOf)
        assertEquals(4, result.signature.branches.size)

        // play 分支：2 个参数
        val playBranch = result.signature.branches[0]
        assertEquals("action=play", playBranch.condition)
        assertEquals(2, playBranch.params.size)
        assertEquals("song", playBranch.params[0].name)
        assertEquals("artist", playBranch.params[1].name)

        // pause 分支：空（无额外参数）
        val pauseBranch = result.signature.branches[1]
        assertEquals("action=pause", pauseBranch.condition)
        assertEquals(0, pauseBranch.params.size)

        // volume 分支：1 个参数
        val volumeBranch = result.signature.branches[2]
        assertEquals("action=volume", volumeBranch.condition)
        assertEquals(1, volumeBranch.params.size)
        assertEquals("volume", volumeBranch.params[0].name)
    }

    @Test
    fun compressedSchemaWithOneOf() {
        val result = compressor.compress("music_control", topOneOfSchema)
        val desc = result.compressedSchema
        assertTrue("action=play" in desc, "play branch missing: $desc")
        assertTrue("song: string" in desc, "song param missing: $desc")
        assertTrue("action=pause" in desc, "pause branch missing: $desc")
        assertTrue(";" in desc, "branches should be separated by ;")
    }

    @Test
    fun oneOfWithEnumDiscriminatorTreatedAsConst() {
        // 单值 enum 等价 const，应当作 discriminator
        val result = compressor.compress("music_control", topOneOfSchema)
        assertTrue(result.signature.isOneOf)
        val volumeBranch = result.signature.branches[2]
        assertEquals("action=volume", volumeBranch.condition)
        assertTrue(volumeBranch.params.none { it.name == "action" })
    }

    @Test
    fun oneOfWithNoDiscriminatorBranchBecomesCatchAll() {
        // 没有 const/单值 enum 的分支（纯 catch-all）应保留，condition 为空
        val result = compressor.compress("music_control", topOneOfSchema)
        val catchAll = result.signature.branches[3]
        assertEquals("", catchAll.condition)
        assertEquals(1, catchAll.params.size)
    }

    @Test
    fun oneOfFormatRendersCatchAllWithAsterisk() {
        // catch-all 分支（空 condition）在签名里用 * 前缀标识
        val result = compressor.compress("music_control", topOneOfSchema)
        val desc = result.compressedSchema
        assertTrue("action=play" in desc, "normal branch missing: $desc")
        assertTrue("; * extra?: string" in desc, "catch-all branch missing or not marked: $desc")
    }

    @Test
    fun topLevelOneOfBranchDescriptionPreserved() {
        val result = compressor.compress("music_control", topOneOfSchema)
        val desc = result.compressedSchema
        assertTrue("播放一首歌曲" in desc, "play branch desc missing: $desc")
        assertTrue("调整音量" in desc, "volume branch desc missing: $desc")
        assertTrue("action=play" in desc, "play condition missing: $desc")
        assertTrue("action=volume" in desc, "volume condition missing: $desc")
    }

    // endregion

    // region 顶层 anyOf / allOf

    @Test
    fun anyOfFallsThroughToOneOf() {
        // anyOf 在压缩语义里和 oneOf 等价
        val result = compressor.compress("payload",
            """{"anyOf":[{"properties":{"kind":{"const":"a"},"a_val":{"type":"string"}},"required":["kind"]},""" +
            """{"properties":{"kind":{"const":"b"},"b_val":{"type":"number"}},"required":["kind"]}]}""")

        assertTrue(result.signature.isOneOf)
        assertEquals(2, result.signature.branches.size)
        assertEquals("kind=a", result.signature.branches[0].condition)
        assertEquals("kind=b", result.signature.branches[1].condition)
    }

    @Test
    fun allOfMergesProperties() {
        // allOf 把多个子 schema 合并（同名字段取第一个，required 取并集）
        val result = compressor.compress("merged",
            """{"allOf":[{"properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name"]},""" +
            """{"properties":{"email":{"type":"string"}},"required":["email"]}]}""")

        assertEquals(3, result.signature.params.size)
        assertTrue(result.signature.params.find { it.name == "name" }!!.required)
        assertTrue(result.signature.params.find { it.name == "email" }!!.required)
        assertFalse(result.signature.params.find { it.name == "age" }!!.required)
    }

    // endregion

    // region 辅助

    private fun extractExecutionDescription(compressedSchema: String): String {
        val schemaJson = Json.parseToJsonElement(compressedSchema)
        return schemaJson.jsonObject["properties"]?.jsonObject
            ?.get("execution")?.jsonObject?.get("description")?.jsonPrimitive?.content ?: ""
    }

    // endregion
}
