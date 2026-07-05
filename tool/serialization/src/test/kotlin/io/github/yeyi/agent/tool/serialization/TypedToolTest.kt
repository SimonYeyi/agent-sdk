package io.github.yeyi.agent.tool.serialization

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ==================== 测试数据类 ====================

@Serializable
data class EmailRequest(
    @Description("收件人邮箱") val to: String,
    @Description("邮件主题") val subject: String,
    val body: String? = null
)

@Serializable
data class SendEmailResult(
    val messageId: String,
    val sentAt: String
)

@Serializable
data class AllTypesRequest(
    @Description("字符串") val str: String,
    @Description("数字") val num: Int,
    @Description("布尔") val bool: Boolean,
    @Description("枚举") val status: Status,
    @Description("字符串数组") val tags: List<String>
)

@Serializable
enum class Status { PENDING, APPROVED, REJECTED }

@Serializable
data class OptionalFieldsRequest(
    @Description("必填字段") val required: String,
    @Description("可选字段") val optional: String? = null
)

// ==================== oneOf 测试数据类 ====================

@Serializable
sealed class MusicAction {
    @Serializable
    @SerialName("play")
    data class Play(val song: String, val artist: String? = null) : MusicAction()

    @Serializable
    @SerialName("pause")
    data class Pause(val duration: Int? = null) : MusicAction()

    @Serializable
    @SerialName("volume")
    data class Volume(val level: Int) : MusicAction()

    @Serializable
    @SerialName("stop")
    object Stop : MusicAction()
}

@Serializable
data class MusicControlRequest(
    @Description("音乐操作")
    val action: MusicAction
)

// ==================== Tool 实现 ====================

class TypedToolTestImpl : TypedTool<EmailRequest, SendEmailResult>(
    parameterType = TypeToken<EmailRequest>(),
    resultType = TypeToken<SendEmailResult>()
) {
    override val name: String = "send_email"
    override val description: String = "发送邮件"

    override suspend fun execute(parameters: EmailRequest, context: ToolContext): SendEmailResult {
        assertEquals("x@x.com", parameters.to)
        assertEquals("hello", parameters.subject)
        assertEquals(null, parameters.body)
        return SendEmailResult("msg-123", "2024-01-01")
    }
}

class AllTypesToolImpl : TypedTool<AllTypesRequest, Unit>(
    parameterType = TypeToken<AllTypesRequest>(),
    resultType = TypeToken()
) {
    override val name: String = "all_types"
    override val description: String = "测试所有类型"

    override suspend fun execute(parameters: AllTypesRequest, context: ToolContext): Unit {
        assertEquals("test", parameters.str)
        assertEquals(42, parameters.num)
        assertEquals(true, parameters.bool)
        assertEquals(Status.PENDING, parameters.status)
        assertEquals(listOf("a", "b"), parameters.tags)
    }
}

class OptionalFieldsToolImpl : TypedTool<OptionalFieldsRequest, Unit>(
    parameterType = TypeToken<OptionalFieldsRequest>(),
    resultType = TypeToken()
) {
    override val name: String = "optional_fields"
    override val description: String = "测试可选字段"

    override suspend fun execute(parameters: OptionalFieldsRequest, context: ToolContext): Unit {
        assertEquals("required_value", parameters.required)
    }
}

// ==================== 辅助函数 ====================

private fun createToolContext(): ToolContext = ToolContext(
    toolCallId = "test-call",
    agentContext = AgentContext(
        persona = Persona(""),
        maxIterations = 10,
        currentIteration = 0,
        memory = InMemoryMemory(),
        llmProvider = object : LlmProvider {
            override val name: String = "test"
            override suspend fun chat(request: ChatRequest): io.github.yeyi.agent.llm.ChatResponse =
                throw UnsupportedOperationException()
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flowOf()
        },
        tools = emptyList(),
        maxRounds = 10
    )
)

// ==================== 测试用例 ====================

class TypedToolTest {

    @Test
    fun `execute parses JSON to typed object`() = runTest {
        val tool = TypedToolTestImpl()
        val json = Json.parseToJsonElement("""{"to":"x@x.com","subject":"hello","body":null}""")

        val result = tool.execute(json, createToolContext())

        assertEquals(false, result.isError)
        assertTrue(result.content.contains("msg-123"))
    }

    @Test
    fun `execute returns JSON result`() = runTest {
        val tool = TypedToolTestImpl()
        val json = Json.parseToJsonElement("""{"to":"x@x.com","subject":"hello","body":null}""")

        val result = tool.execute(json, createToolContext())

        // 验证返回的是 JSON 格式
        val parsed = Json.parseToJsonElement(result.content)
        assertTrue(parsed.toString().contains("msg-123"))
    }

    @Test
    fun `parametersSchema contains standard JSON Schema with descriptions`() {
        val tool = TypedToolTestImpl()
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        assertTrue(schema.schema.contains("\"to\""), "schema should contain 'to'")
        assertTrue(schema.schema.contains("\"subject\""), "schema should contain 'subject'")
        assertTrue(schema.schema.contains("\"body\""), "schema should contain 'body'")
        assertTrue(schema.schema.contains("收件人邮箱"), "schema should contain description for 'to'")
        assertTrue(schema.schema.contains("邮件主题"), "schema should contain description for 'subject'")
    }

    @Test
    fun `parametersSchema contains all type mappings`() {
        val tool = AllTypesToolImpl()
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        assertTrue(schema.schema.contains("\"str\""), "schema should contain 'str'")
        assertTrue(schema.schema.contains("\"num\""), "schema should contain 'num'")
        assertTrue(schema.schema.contains("\"bool\""), "schema should contain 'bool'")
        assertTrue(schema.schema.contains("\"status\""), "schema should contain 'status'")
        assertTrue(schema.schema.contains("\"tags\""), "schema should contain 'tags'")
        // 验证类型
        assertTrue(schema.schema.contains("\"string\""), "schema should contain string type")
        assertTrue(schema.schema.contains("\"number\""), "schema should contain number type")
        assertTrue(schema.schema.contains("\"boolean\""), "schema should contain boolean type")
        // 验证枚举
        assertTrue(schema.schema.contains("\"enum\""), "schema should contain enum")
        assertTrue(schema.schema.contains("PENDING"), "schema should contain enum value PENDING")
        assertTrue(schema.schema.contains("APPROVED"), "schema should contain enum value APPROVED")
        assertTrue(schema.schema.contains("REJECTED"), "schema should contain enum value REJECTED")
    }

    @Test
    fun `parametersSchema is valid JSON with correct structure`() {
        val tool = AllTypesToolImpl()
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject

        assertEquals("object", json["type"]!!.jsonPrimitive.content)

        val properties = json["properties"]!!.jsonObject

        // 验证基本类型
        assertEquals("string", properties["str"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", properties["num"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", properties["bool"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // 验证枚举
        val status = properties["status"]!!.jsonObject
        assertEquals("string", status["type"]!!.jsonPrimitive.content)
        val enumValues = status["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PENDING", "APPROVED", "REJECTED"), enumValues)
    }

    @Test
    fun `execute parses all types correctly`() = runTest {
        val tool = AllTypesToolImpl()
        val json = Json.parseToJsonElement("""{"str":"test","num":42,"bool":true,"status":"PENDING","tags":["a","b"]}""")

        val result = tool.execute(json, createToolContext())

        assertEquals(false, result.isError)
    }

    @Test
    fun `execute parses optional fields`() = runTest {
        val tool = TypedToolTestImpl()
        // body 为 null（可选字段）
        val json = Json.parseToJsonElement("""{"to":"x@x.com","subject":"hello"}""")

        val result = tool.execute(json, createToolContext())

        assertEquals(false, result.isError)
    }

    @Test
    fun `typedTool factory creates tool without subclassing`() = runTest {
        val tool = tool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { params, ctx ->
            assertEquals("x@x.com", params.to)
            assertEquals("hello", params.subject)
            SendEmailResult("msg-123", "2024-01-01")
        }

        assertEquals("send_email", tool.name)
        assertEquals("发送邮件", tool.description)

        val json = Json.parseToJsonElement("""{"to":"x@x.com","subject":"hello"}""")
        val result = tool.execute(json, createToolContext())

        assertEquals(false, result.isError)
        assertTrue(result.content.contains("msg-123"))
    }

    @Test
    fun `typedTool factory generates schema with descriptions`() {
        val tool = tool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { params, ctx ->
            SendEmailResult("msg-123", "2024-01-01")
        }

        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        assertTrue(schema.schema.contains("\"to\""), "schema should contain 'to'")
        assertTrue(schema.schema.contains("\"subject\""), "schema should contain 'subject'")
        assertTrue(schema.schema.contains("收件人邮箱"), "schema should contain description")
    }

    @Test
    fun `typedTool generates oneOf schema for sealed class`() {
        val tool = tool<MusicControlRequest, String>("music_control", "音乐控制") { params, ctx ->
            "ok"
        }

        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema)

        // 验证有 oneOf 结构
        val actionField = json.jsonObject["properties"]?.jsonObject?.get("action")?.jsonObject
        assertTrue(actionField?.containsKey("oneOf") == true, "schema should contain oneOf: $schema")

        // 验证 oneOf 分支
        val oneOfArray = actionField?.get("oneOf")?.jsonArray
        assertEquals(2, oneOfArray?.size, "should have 2 branches: $oneOfArray")

        // 验证 discriminator 值
        val branches = oneOfArray?.map { it.jsonObject } ?: emptyList()
        assertTrue(branches.any { it.toString().contains("\"play\"") }, "should have play branch")
        assertTrue(branches.any { it.toString().contains("\"pause\"") }, "should have pause branch")
    }

    @Test
    fun `typedTool oneOf schema with const discriminator`() {
        val tool = tool<MusicControlRequest, String>("music_control", "音乐控制") { params, ctx ->
            "ok"
        }

        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema)

        val actionField = json.jsonObject["properties"]?.jsonObject?.get("action")?.jsonObject
        val oneOfArray = actionField?.get("oneOf")?.jsonArray

        // 验证每个分支都有 type 字段
        oneOfArray?.forEach { branch ->
            val branchObj = branch.jsonObject
            assertTrue(branchObj.containsKey("type") || branchObj.containsKey("properties"),
                "branch should have type or properties: $branchObj")
        }
    }
}
