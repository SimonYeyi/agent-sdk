package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// region 公共 schema —— 3 份 schema 覆盖多组测试场景

/** 简单 object：to + subject（required to） */
private val SEND_EMAIL_SCHEMA = """
    {
        "type": "object",
        "properties": {
            "to": { "type": "string" },
            "subject": { "type": "string" }
        },
        "required": ["to"]
    }
""".trimIndent()

/** 嵌套 oneOf 字段：channel 按 type 判别 email/webhook */
private val NESTED_ONEOF_CHANNEL_SCHEMA = """
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
""".trimIndent()

/** 数组元素是 oneOf：events 按 type 判别 click/view */
private val ARRAY_ONEOF_EVENTS_SCHEMA = """
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
""".trimIndent()

// endregion

private fun createTool(name: String, schema: String): Tool = object : Tool {
    override val name: String = name
    override val description: String = "test"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(schema)
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        return ToolExecutionResult.success(arguments.toString())
    }
}

private fun createEmptyTool(name: String): Tool = object : Tool {
    override val name: String = name
    override val description: String = "test"
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        return ToolExecutionResult.success(arguments.toString())
    }
}

private fun createToolContext(): ToolContext = ToolContext(
    toolCallId = "test-call",
    agentContext = AgentContext(
        persona = Persona(""),
        maxIterations = 10,
        currentIteration = 0,
        memory = InMemoryMemory(),
        llmProvider = object : LlmProvider {
            override val name: String = "test"
            override suspend fun chat(request: ChatRequest): ChatResponse = throw UnsupportedOperationException()
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flowOf()
        },
        tools = emptyList(),
        maxRounds = 10
    )
)

class CompressToolTest {

    @Test
    fun `parametersSchema returns compressed schema for JsonSchema tool`() {
        val tool = createTool("send_email", SEND_EMAIL_SCHEMA)

        val compressed = CompressTool(tool)

        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        assertTrue(schema.schema.contains("execution"))
        assertTrue(schema.schema.contains("send_email(to: string"))
    }

    @Test
    fun `parametersSchema returns Empty for Empty tool`() {
        val tool = createEmptyTool("noop")

        val compressed = CompressTool(tool)

        assertIs<ToolParameters.Empty>(compressed.parametersSchema)
    }

    @Test
    fun `execute parses execution string for JsonSchema tool`() = runTest {
        val tool = createTool("send_email", SEND_EMAIL_SCHEMA)

        val compressed = CompressTool(tool)
        compressed.parametersSchema

        val result = compressed.execute(
            Json.parseToJsonElement("""{"execution":"send_email(to='x@x.com', subject='hello')"}"""),
            createToolContext()
        )

        assertEquals(false, result.isError)
        assertTrue(result.content.contains("x@x.com"))
        assertTrue(result.content.contains("hello"))
    }

    @Test
    fun `execute passes through arguments for Empty tool`() = runTest {
        val tool = createEmptyTool("noop")

        val compressed = CompressTool(tool)

        val result = compressed.execute(
            Json.parseToJsonElement("""{"foo":"bar"}"""),
            createToolContext()
        )

        assertEquals(false, result.isError)
        assertTrue(result.content.contains("foo"))
    }

    @Test
    fun `parametersSchema returns original for tiny JsonSchema tool`() = runTest {
        // 极简 schema 压缩后包装层(~100 字符)反而更长,应跳过压缩直接返回原 schema
        val tinySchema = """{"type":"object","properties":{"numbers":{"type":"array","items":{"type":"number"}}},"required":["numbers"]}"""
        val tool = createTool("add", tinySchema)

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema

        // 验证:返回的是原 schema,不含 execution 包装
        assertEquals(tinySchema, schema.schema)
        assertFalse(schema.schema.contains("execution"))
    }

    @Test
    fun `parametersSchema returns original when description dominates schema`() = runTest {
        // 用户编写的 description 无法压缩,若 schema 主要由 description 构成,
        // 压缩后反而比原版更长(包装层 + 不可压缩的 description)。跳过压缩。
        val descriptionHeavySchema = """{"type":"object","properties":{"numbers":{"type":"array","items":{"type":"number"},"description":"The numbers to add.The numbers to add.The numbers to add.The numbers to add.The numbers to add."}},"required":["numbers"]}"""
        val tool = createTool("add", descriptionHeavySchema)

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema

        // 验证:压缩后更长 → 返回原 schema,不含 execution
        assertEquals(descriptionHeavySchema, schema.schema)
        assertFalse(schema.schema.contains("execution"))
    }

    @Test
    fun `execute passes through arguments when compression is skipped`() = runTest {
        // 极简 schema 跳过压缩时,execute 不应期望 {execution: ...} 包裹,直接透传 arguments
        val tinySchema = """{"type":"object","properties":{"numbers":{"type":"array","items":{"type":"number"}}},"required":["numbers"]}"""
        val tool = createTool("add", tinySchema)

        val compressed = CompressTool(tool)
        compressed.parametersSchema // 触发懒加载

        // 透传模式:arguments 直接是 {numbers: [1,2,3]},不应包 execution
        val result = compressed.execute(
            Json.parseToJsonElement("""{"numbers":[1,2,3]}"""),
            createToolContext()
        )

        assertFalse(result.isError, "execute failed: ${result.content}")
        assertTrue(result.content.contains("numbers"))
    }

    @Test
    fun `nested oneOf round-trip -- channel field with type discriminator`() = runTest {
        // 顶层普通字段 channel 是 oneOf:按 type 判别,后续字段分派到对应分支
        val tool = createTool("send", NESTED_ONEOF_CHANNEL_SCHEMA)

        val compressed = CompressTool(tool)
        compressed.parametersSchema // 触发压缩

        val execution = """send(channel={type=email, to='user@example.com'})"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        assertFalse(result.isError, "execute failed: ${result.content}")
        val parsed = Json.parseToJsonElement(result.content).jsonObject
        val channel = parsed["channel"]!!.jsonObject
        assertEquals("email", channel["type"]?.jsonPrimitive?.content)
        assertEquals("user@example.com", channel["to"]?.jsonPrimitive?.content)
    }

    @Test
    fun `nested oneOf array element round-trip`() = runTest {
        // events 是 array of oneOf:每个元素按自己的 type 判别分派
        val tool = createTool("log", ARRAY_ONEOF_EVENTS_SCHEMA)

        val compressed = CompressTool(tool)
        compressed.parametersSchema

        val execution = """log(events=[{type=click, x=10, y=20}, {type=view, page='/home'}])"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        assertFalse(result.isError, "execute failed: ${result.content}")
        val parsed = Json.parseToJsonElement(result.content).jsonObject
        val events = parsed["events"]!!.jsonArray
        assertEquals(2, events.size)
        assertEquals("click", events[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(10.0, events[0].jsonObject["x"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("view", events[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("/home", events[1].jsonObject["page"]?.jsonPrimitive?.content)
    }

    @Test
    fun `comprehensive end-to-end demo for full schema compression`() = runBlocking {
        // 覆盖:顶层普通字段 + 嵌套 oneOf 字段 + 数组元素 oneOf +
        //      嵌套 object(含三层嵌套) + 数组 of object + 各种原始类型 + 枚举 + 必选/可选 +
        //      嵌套 boolean + array of integer + 未知字段降级
        val tool = createTool("send_notification", """
            {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "通知标题"},
                    "priority": {
                        "type": "string",
                        "enum": ["low", "normal", "high"],
                        "description": "优先级"
                    },
                    "recipients": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "收件人列表"
                    },
                    "channel": {
                        "description": "发送渠道",
                        "oneOf": [
                            {
                                "properties": {
                                    "type": {"const": "email"},
                                    "to": {"type": "string"},
                                    "subject": {"type": "string"}
                                },
                                "required": ["type", "to"]
                            },
                            {
                                "properties": {
                                    "type": {"const": "sms"},
                                    "phone": {"type": "string"}
                                },
                                "required": ["type", "phone"]
                            },
                            {
                                "properties": {
                                    "type": {"const": "webhook"},
                                    "url": {"type": "string"},
                                    "retries": {"type": "integer"}
                                },
                                "required": ["type", "url"]
                            }
                        ]
                    },
                    "schedule": {
                        "type": "object",
                        "description": "可选的定时配置",
                        "properties": {
                            "send_at": {"type": "string", "description": "ISO 时间"},
                            "timezone": {"type": "string", "description": "IANA 时区"},
                            "enabled": {"type": "boolean", "description": "是否启用定时"},
                            "retry_config": {
                                "type": "object",
                                "description": "重试配置",
                                "properties": {
                                    "max_attempts": {"type": "integer", "description": "最大重试次数"},
                                    "strategy": {"type": "string", "enum": ["fixed", "exponential"], "description": "重试策略"}
                                },
                                "required": ["max_attempts"]
                            },
                            "reminders": {
                                "type": "array",
                                "description": "提醒列表",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "message": {"type": "string", "description": "提醒内容"},
                                        "offset_min": {"type": "integer", "description": "提前分钟数"}
                                    },
                                    "required": ["message"]
                                }
                            }
                        },
                        "required": ["send_at"]
                    },
                    "attachments": {
                        "type": "array",
                        "description": "附件列表",
                        "items": {
                            "type": "object",
                            "properties": {
                                "filename": {"type": "string", "description": "文件名"},
                                "size": {"type": "integer", "description": "文件大小(字节)"}
                            },
                            "required": ["filename"]
                        }
                    },
                    "events": {
                        "type": "array",
                        "description": "埋点事件(用于分析通知效果)",
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
                                        "page": {"type": "string"},
                                        "duration_ms": {"type": "integer"},
                                        "impact_ids": {"type": "array", "items": {"type": "integer"}}
                                    },
                                    "required": ["type", "page"]
                                }
                            ]
                        }
                    }
                },
                "required": ["title", "priority", "channel", "recipients"]
            }
        """.trimIndent())

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        // 压缩签名断言:覆盖嵌套结构渲染
        // 三层嵌套:schedule.retry_config.max_attempts 是 required(无 ?),integer 压缩为 number
        assertTrue("max_attempts: number" in desc, "required number (no ?) missing: $desc")
        // 嵌套 enum:retry_config.strategy（optional,渲染为 strategy?:）
        assertTrue("strategy?: enum(fixed, exponential)" in desc, "nested enum missing: $desc")
        // optional array of object:attachments?:[{filename: string}]
        assertTrue("attachments?:" in desc, "optional array of object missing: $desc")
        // 嵌套 boolean:schedule.enabled
        assertTrue("enabled?: boolean" in desc, "nested boolean missing: $desc")
        // array of integer in oneOf:impact_ids（integer 压缩为 number）
        assertTrue("impact_ids?: number[]" in desc, "array of integer missing: $desc")

        // 一个走通所有特性的 execution:嵌套 oneOf 走 email 分支,
        // 数组里有 click 和 view 两种 oneOf 元素,schedule 三层嵌套,
        // attachments 是 optional array of object,view 分支带 impact_ids 整数数组,
        // unknown_field 不在 schema 里——演示未知字段降级为 StringType
        val execution = """send_notification(
            title='新功能发布',
            priority=high,
            recipients=['alice@x.com', 'bob@x.com'],
            channel={type=email, to='team@x.com', subject='v2.0 来了'},
            schedule={send_at='2026-08-01T10:00:00Z', timezone='Asia/Shanghai', enabled=true, retry_config={max_attempts=3, strategy=exponential}, unknown_field='demo', reminders=[{message='准备材料', offset_min=30}, {message='开始会议'}]},
            attachments=[{filename='report.pdf', size=1024}],
            events=[{type=click, x=120, y=340}, {type=view, page='/home', duration_ms=2500, impact_ids=[1, 2, 3]}]
        )"""

        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution.replace("\n", "").replace(Regex("\\s+"), " ").trim()))),
            createToolContext()
        )

        // 断言:解析结果里所有字段类型正确
        val parsed = Json.parseToJsonElement(result.content).jsonObject
        assertEquals(false, result.isError, "execute failed: ${result.content}")
        assertEquals("新功能发布", parsed["title"]?.jsonPrimitive?.content)
        assertEquals("high", parsed["priority"]?.jsonPrimitive?.content)
        val recipients = parsed["recipients"]!!.jsonArray
        assertEquals(2, recipients.size)
        assertEquals("alice@x.com", recipients[0].jsonPrimitive.content)
        assertEquals("bob@x.com", recipients[1].jsonPrimitive.content)
        val channel = parsed["channel"]!!.jsonObject
        assertEquals("email", channel["type"]?.jsonPrimitive?.content)
        assertEquals("team@x.com", channel["to"]?.jsonPrimitive?.content)
        assertEquals("v2.0 来了", channel["subject"]?.jsonPrimitive?.content)
        val schedule = parsed["schedule"]!!.jsonObject
        assertEquals("2026-08-01T10:00:00Z", schedule["send_at"]?.jsonPrimitive?.content)
        assertEquals("Asia/Shanghai", schedule["timezone"]?.jsonPrimitive?.content)
        // 三层嵌套 boolean:schedule.enabled
        assertEquals(true, schedule["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        // 三层嵌套:schedule.retry_config.max_attempts (required integer) + strategy (嵌套 enum)
        val retryConfig = schedule["retry_config"]!!.jsonObject
        assertEquals(3.0, retryConfig["max_attempts"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("exponential", retryConfig["strategy"]?.jsonPrimitive?.content)
        // 未知字段降级为 StringType:unknown_field 不在 schema 里
        assertEquals("demo", schedule["unknown_field"]?.jsonPrimitive?.content)
        // 三层 round-trip:schedule.reminders[] 是 array of object
        val reminders = schedule["reminders"]!!.jsonArray
        assertEquals(2, reminders.size)
        assertEquals("准备材料", reminders[0].jsonObject["message"]?.jsonPrimitive?.content)
        assertEquals(30.0, reminders[0].jsonObject["offset_min"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("开始会议", reminders[1].jsonObject["message"]?.jsonPrimitive?.content)
        assertNull(reminders[1].jsonObject["offset_min"])
        // optional array of object:attachments
        val attachments = parsed["attachments"]!!.jsonArray
        assertEquals(1, attachments.size)
        assertEquals("report.pdf", attachments[0].jsonObject["filename"]?.jsonPrimitive?.content)
        assertEquals(1024.0, attachments[0].jsonObject["size"]?.jsonPrimitive?.content?.toDouble())
        val events = parsed["events"]!!.jsonArray
        assertEquals(2, events.size)
        val click = events[0].jsonObject
        assertEquals("click", click["type"]?.jsonPrimitive?.content)
        assertEquals(120.0, click["x"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(340.0, click["y"]?.jsonPrimitive?.content?.toDouble())
        val view = events[1].jsonObject
        assertEquals("view", view["type"]?.jsonPrimitive?.content)
        assertEquals("/home", view["page"]?.jsonPrimitive?.content)
        assertEquals(2500.0, view["duration_ms"]?.jsonPrimitive?.content?.toDouble())
        // array of integer:impact_ids
        val impactIds = view["impact_ids"]!!.jsonArray
        assertEquals(3, impactIds.size)
        assertEquals(1.0, impactIds[0].jsonPrimitive.content.toDouble())
        assertEquals(2.0, impactIds[1].jsonPrimitive.content.toDouble())
        assertEquals(3.0, impactIds[2].jsonPrimitive.content.toDouble())
    }

    @Test
    fun `demo printout for nested oneOf`() = runBlocking {
        val tool = createTool("send", NESTED_ONEOF_CHANNEL_SCHEMA)

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        val execution = """send(channel={type=email, to='a@b.com'})"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

    }

    @Test
    fun `demo printout for oneOf as array element`() = runBlocking {
        val tool = createTool("log", ARRAY_ONEOF_EVENTS_SCHEMA)

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        val execution = """log(events=[{type=click, x=10, y=20}, {type=view, page='/home'}])"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

    }
}

