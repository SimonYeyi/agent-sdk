package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
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
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flowOf()
        },
        tools = emptyList(),
        maxRounds = 10
    )
)

class CompressToolTest {

    @Test
    fun `parametersSchema returns compressed schema for JsonSchema tool`() {
        val tool = createTool("send_email", """
            {
                "type": "object",
                "properties": {
                    "to": { "type": "string" },
                    "subject": { "type": "string" }
                },
                "required": ["to"]
            }
        """.trimIndent())

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
        val tool = createTool("send_email", """
            {
                "type": "object",
                "properties": {
                    "to": { "type": "string" },
                    "subject": { "type": "string" }
                },
                "required": ["to"]
            }
        """.trimIndent())

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
    fun `mcp_bug_handle compresses save_bugs as top-level parameter with nested structure`() {
        // parseType 现在递归处理 properties,内层对象/对象数组都会渲染进签名。
        // LLM 这次能看到 bugs 数组里有哪些字段,以及它们的类型和枚举值。
        val compressed = CompressTool(createTool("mcp_bug_handle", MCP_BUG_HANDLE_SCHEMA))
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema

        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val executionDesc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        // 顶层:save_bugs 带 description
        assertTrue("save_bugs" in executionDesc, "save_bugs missing: $executionDesc")
        assertTrue("\"保存 Bug" in executionDesc, "save_bugs description missing: $executionDesc")
        // 一层嵌套:{ bugs?: [...] } — bugs 是 optional(没在 required 数组里),所以有 ? 后缀
        assertTrue("bugs?:" in executionDesc, "nested bugs field missing: $executionDesc")
        assertTrue("[" in executionDesc && "{bugs?:" in executionDesc,
            "array-of-object marker missing: $executionDesc")
        // 二层嵌套:mode 是必填的 enum,带所有 enum 值
        assertTrue("mode: enum(" in executionDesc, "mode enum missing: $executionDesc")
        assertTrue("\"add\"" in executionDesc || "add," in executionDesc,
            "enum value 'add' missing: $executionDesc")
        assertTrue("\"update_fields\"" in executionDesc || "update_fields" in executionDesc,
            "enum value 'update_fields' missing: $executionDesc")
        // 可选字段带 ? 后缀
        assertTrue("title?:" in executionDesc, "optional marker missing: $executionDesc")
        // severity 在 impacts items 里是 required,渲染成 severity: number(无 ?)
        assertTrue("severity: number" in executionDesc, "severity should be required number: $executionDesc")
    }

    @Test
    fun `mcp_bug_handle round-trip -- nested fields preserved through new syntax`() = runTest {
        val compressed = CompressTool(createTool("mcp_bug_handle", MCP_BUG_HANDLE_SCHEMA))
        compressed.parametersSchema // 触发 lazy 压缩

        // 新语法:嵌套层统一用 key=value,内层对象 { }、数组 [ ],跟顶层一致。
        val execution = """mcp_bug_handle(save_bugs={bugs=[{mode='add', title='T', phenomenon='P'}]})"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        assertFalse(result.isError, "execute failed: ${result.content}")
        val parsed = Json.parseToJsonElement(result.content).jsonObject

        assertTrue("save_bugs" in parsed, "save_bugs key missing in parsed: $parsed")
        val saveBugs = parsed["save_bugs"]!!.jsonObject
        val bugs = saveBugs["bugs"]!!.jsonArray
        assertEquals(1, bugs.size)
        val firstBug = bugs[0].jsonObject
        assertEquals("add", firstBug["mode"]!!.jsonPrimitive.content)
        assertEquals("T", firstBug["title"]!!.jsonPrimitive.content)
        assertEquals("P", firstBug["phenomenon"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested oneOf round-trip -- channel field with type discriminator`() = runTest {
        // 顶层普通字段 channel 是 oneOf:按 type 判别,后续字段分派到对应分支
        val tool = createTool("send", """
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
        val tool = createTool("log", """
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
        //      嵌套 object + 数组 of object + 各种原始类型 + 枚举 + 必选/可选
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
                            "timezone": {"type": "string", "description": "IANA 时区"}
                        },
                        "required": ["send_at"]
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
                                        "duration_ms": {"type": "integer"}
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

        // 一个走通所有特性的 execution:嵌套 oneOf 走 email 分支,
        // 数组里有 click 和 view 两种 oneOf 元素,schedule 只填必填字段
        val execution = """send_notification(
            title='新功能发布',
            priority=high,
            recipients=['alice@x.com', 'bob@x.com'],
            channel={type=email, to='team@x.com', subject='v2.0 来了'},
            schedule={send_at='2026-08-01T10:00:00Z', timezone='Asia/Shanghai'},
            events=[{type=click, x=120, y=340}, {type=view, page='/home', duration_ms=2500}]
        )"""

        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution.replace("\n", "").replace(Regex("\\s+"), " ").trim()))),
            createToolContext()
        )

        java.io.File("D:/yeyi/AI/agent-sdk/build/comprehensive-demo-output.txt").writeText(buildString {
            appendLine("=== 综合示例:覆盖所有压缩特性 ===")
            appendLine()
            appendLine("=== [1] 压缩后的完整 JSON Schema ===")
            appendLine(Json.parseToJsonElement(schema.schema).toString())
            appendLine()
            appendLine("=== [2] execution.description (签名) ===")
            appendLine(desc)
            appendLine()
            appendLine("=== [3] LLM 生成的 execution 串 ===")
            appendLine(execution.replace("\n", "").replace(Regex("\\s+"), " ").trim())
            appendLine()
            appendLine("=== [4] 解析回 JSON (喂给下游 tool.execute) ===")
            appendLine(Json.parseToJsonElement(result.content).toString())
            appendLine()
            appendLine("=== isError ===")
            appendLine(result.isError)
            appendLine()
        })

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
    }

    @Test
    fun `demo printout for nested oneOf`() = runBlocking {
        val tool = createTool("send", """
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

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        val execution = """send(channel={type=email, to='a@b.com'})"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        java.io.File("D:/yeyi/AI/agent-sdk/build/nested-oneof-output.txt").writeText(buildString {
            appendLine("=== 嵌套 oneOf 字段(在 object 里) ===")
            appendLine()
            appendLine("=== [1] 签名 ===")
            appendLine(desc)
            appendLine()
            appendLine("=== [2] execution 串 ===")
            appendLine(execution)
            appendLine()
            appendLine("=== [3] 解析回 JSON ===")
            appendLine(Json.parseToJsonElement(result.content).toString())
            appendLine()
        })
    }

    @Test
    fun `demo printout for oneOf as array element`() = runBlocking {
        val tool = createTool("log", """
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

        val compressed = CompressTool(tool)
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        val execution = """log(events=[{type=click, x=10, y=20}, {type=view, page='/home'}])"""
        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        java.io.File("D:/yeyi/AI/agent-sdk/build/oneof-array-output.txt").writeText(buildString {
            appendLine("=== 数组元素是 oneOf ===")
            appendLine()
            appendLine("=== [1] 签名 ===")
            appendLine(desc)
            appendLine()
            appendLine("=== [2] execution 串 ===")
            appendLine(execution)
            appendLine()
            appendLine("=== [3] 解析回 JSON ===")
            appendLine(Json.parseToJsonElement(result.content).toString())
            appendLine()
        })
    }

    @Test
    fun `demo round-trip printout for mcp_bug_handle`() = runBlocking {
        val compressed = CompressTool(createTool("mcp_bug_handle", MCP_BUG_HANDLE_SCHEMA))
        val schema = compressed.parametersSchema as ToolParameters.JsonSchema
        val root = Json.parseToJsonElement(schema.schema).jsonObject
        val desc = root["properties"]!!.jsonObject["execution"]!!.jsonObject["description"]!!.jsonPrimitive.content

        // 新嵌套语法:save_bugs 是对象,内层用 { bugs=[{...}] } 跟顶层 key=value 一致。
// 注意 severity 不在 bug item 的顶层 schema 里(它嵌在 impacts 里),所以这里用它
// 演示「未知字段」会降级为 StringType。
        val execution = """mcp_bug_handle(save_bugs={bugs=[{mode='add', title='登录失败', phenomenon='密码错误后仍提示成功'}, {mode='update_fields', id=42, verified=true, impact_ids=[1, 2, 3]}]})"""

        val result = compressed.execute(
            JsonObject(mapOf("execution" to JsonPrimitive(execution))),
            createToolContext()
        )

        java.io.File("D:/yeyi/AI/agent-sdk/build/compression-demo-output.txt").writeText(buildString {
            appendLine("=== [1] 压缩后的 schema (LLM 实际看到的完整 JSON Schema) ===")
            appendLine(Json.parseToJsonElement(schema.schema).toString())
            appendLine()
            appendLine("=== [2] execution.description (签名,即描述字段里的函数调用模板) ===")
            appendLine(desc)
            appendLine()
            appendLine("=== [3] 根据签名填参数后 LLM 生成的 execution 串 ===")
            appendLine(execution)
            appendLine()
            appendLine("=== [4] 解析回来的 JSON (喂给下游 tool.execute 的 arguments) ===")
            appendLine(Json.parseToJsonElement(result.content).toString())
            appendLine()
            appendLine("=== isError ===")
            appendLine(result.isError)
        })
    }
}

// bug_book 里 mcp_bug_handle 工具的参数 schema 真实写法:save_bugs 是唯一参数键
// (object 类型,其中再有 bugs 数组)。这里我们手动包一层标准的 envelope
// {type, properties, required},让 SchemaCompressor 能识别 save_bugs 是顶层参数。
private val MCP_BUG_HANDLE_SCHEMA = """
{
    "type": "object",
    "properties": {
        "save_bugs": {
            "type": "object",
            "description": "保存 Bug（新增、更新或删除），支持批量操作",
            "properties": {
                "bugs": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "integer", "description": "Bug ID（add 模式禁止传入，其他 mode 必填）"},
                            "mode": {
                                "type": "string",
                                "enum": [
                                    "add", "update_fields", "delete",
                                    "add_impacts", "remove_impacts", "replace_impacts",
                                    "add_paths", "remove_paths", "replace_paths",
                                    "add_module_patterns", "remove_module_patterns", "replace_module_patterns",
                                    "add_keywords", "remove_keywords", "replace_keywords",
                                    "add_tags", "remove_tags", "replace_tags",
                                    "increment_scores", "decrement_scores", "replace_scores",
                                ],
                                "description": "操作模式：add(新增)/update_fields(更新字段)/delete(删除)/add_impacts(添加影响)/remove_impacts(移除影响)/replace_impacts(替换影响)/add_paths(添加路径)/remove_paths(移除路径)/replace_paths(替换路径)/add_module_patterns(添加模块)/remove_module_patterns(移除模块)/replace_module_patterns(替换模块)/add_keywords(添加关键词)/remove_keywords(移除关键词)/replace_keywords(替换关键词)/add_tags(添加标签)/remove_tags(移除标签)/replace_tags(替换标签)/increment_scores(累加分数)/decrement_scores(扣减分数)/replace_scores(替换分数)"
                            },
                            "title": {"type": "string", "description": "标题（add 模式必填）"},
                            "phenomenon": {"type": "string", "description": "现象描述（add 模式必填）"},
                            "root_cause": {"type": "string", "description": "根本原因（update_fields 模式可选）"},
                            "solution": {"type": "string", "description": "解决方案（update_fields 模式可选）"},
                            "test_case": {"type": "string", "description": "测试用例（update_fields 模式可选）"},
                            "status": {"type": "string", "enum": ["active", "resolved", "invalid"], "description": "状态（update_fields 模式可选）"},
                            "verified": {"type": "boolean", "description": "是否验证（update_fields 模式可选）"},
                            "verified_at": {"type": "string", "description": "验证时间（update_fields 模式可选）"},
                            "verified_by": {"type": "string", "description": "验证人（update_fields 模式可选）"},

                            "impacts": {
                                "type": "array",
                                "description": "影响关系数组（add_impacts/replace_impacts 模式必填）",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "solution_change": {"type": "string", "description": "产生影响的详细具体的方案"},
                                        "impact_description": {"type": "string", "description": "方案导致的具体影响描述"},
                                        "impact_type": {"type": "string", "enum": ["regression", "side_effect", "dependency"], "description": "影响类型"},
                                        "severity": {"type": "integer", "minimum": 0, "maximum": 10, "description": "严重程度（0-10）"},
                                    },
                                    "required": ["solution_change", "impact_description", "impact_type", "severity"]
                                }
                            },
                            "impact_ids": {"type": "array", "items": {"type": "integer"}, "description": "要移除的影响关系ID数组（remove_impacts 模式必填）"},

                            "paths": {
                                "type": "array",
                                "description": "Bug 出现的位置（add_paths/replace_paths/remove_paths 模式必填）。每个元素为对象 {file, functions?}，functions 可选；add_paths/replace_paths 时必须传 functions 精确到函数；remove_paths 时不传 functions 删除整个文件，传 functions 只删除指定函数",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "file": {"type": "string", "description": "文件路径"},
                                        "functions": {"type": "array", "items": {"type": "string"}, "description": "函数名列表（可选）"},
                                    },
                                    "required": ["file"]
                                }
                            },

                            "module_patterns": {"type": "array", "items": {"type": "string"}, "description": "模块模式数组（add_module_patterns/remove_module_patterns/replace_module_patterns 模式必填）"},

                            "keywords": {"type": "array", "items": {"type": "string"}, "description": "关键词数组（add_keywords/remove_keywords/replace_keywords 模式必填）"},

                            "tags": {"type": "array", "items": {"type": "string"}, "description": "标签数组（add_tags/remove_tags/replace_tags 模式必填）"},

                            "scores": {
                                "type": "object",
                                "description": "分数字典（increment_scores/decrement_scores/replace_scores 模式必填）",
                                "properties": {
                                    "importance": {"type": "number", "description": "重要性分数"},
                                    "complexity": {"type": "number", "description": "复杂度分数"},
                                    "scope": {"type": "number", "description": "影响范围分数"},
                                    "difficulty": {"type": "number", "description": "修复难度分数"},
                                    "occurrences": {"type": "number", "description": "出现次数分数"},
                                    "emotion": {"type": "number", "description": "情绪影响分数"},
                                    "prevention": {"type": "number", "description": "预防价值分数"},
                                },
                            },
                        },
                        "required": ["mode"],
                    },
                },
            },
        },
    },
    "required": ["save_bugs"]
}
""".trimIndent()
