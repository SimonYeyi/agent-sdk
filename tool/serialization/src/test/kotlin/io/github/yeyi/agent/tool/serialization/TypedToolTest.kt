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
import kotlinx.serialization.json.JsonClassDiscriminator
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

// 自定义 discriminator 字段名
@Serializable
@JsonClassDiscriminator("event")
sealed class DeviceEvent {
    @Serializable
    @SerialName("turned_on")
    data class TurnedOn(val deviceId: String) : DeviceEvent()

    @Serializable
    @SerialName("turned_off")
    data class TurnedOff(val deviceId: String) : DeviceEvent()
}

@Serializable
data class DeviceControlRequest(
    @Description("设备事件")
    val event: DeviceEvent
)

// ==================== 嵌套结构测试数据类 ====================

@Serializable
data class Address(
    @Description("街道") val street: String,
    val city: String? = null
)

@Serializable
data class NestedRequest(
    @Description("用户名") val name: String,
    val address: Address,
    val tags: List<String>,
    val scores: List<Int>? = null
)

@Serializable
sealed class NotificationChannel {
    @Serializable
    @SerialName("email")
    data class Email(@Description("收件人") val to: String) : NotificationChannel()

    @Serializable
    @SerialName("sms")
    data class Sms(@Description("手机号") val phone: String) : NotificationChannel()
}

@Serializable
data class NotifyRequest(
    @Description("渠道") val channel: NotificationChannel,
    val fallbacks: List<NotificationChannel>? = null
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
        assertEquals(4, oneOfArray?.size, "should have 4 branches: $oneOfArray")

        // 验证 discriminator 值
        val branches = oneOfArray?.map { it.jsonObject } ?: emptyList()
        assertTrue(branches.any { it.toString().contains("\"play\"") }, "should have play branch")
        assertTrue(branches.any { it.toString().contains("\"pause\"") }, "should have pause branch")
        assertTrue(branches.any { it.toString().contains("\"volume\"") }, "should have volume branch")
        assertTrue(branches.any { it.toString().contains("\"stop\"") }, "should have stop branch")
    }

    @Test
    fun `typedTool oneOf schema includes object subclass`() {
        val tool = tool<MusicControlRequest, String>("music_control", "音乐控制") { params, ctx ->
            "ok"
        }

        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema)

        val actionField = json.jsonObject["properties"]?.jsonObject?.get("action")?.jsonObject
        val oneOfArray = actionField?.get("oneOf")?.jsonArray

        // 验证 Stop 分支存在（object 子类）
        assertTrue(oneOfArray?.any { branch ->
            branch.jsonObject.toString().contains("\"stop\"")
        } == true, "should have stop branch: $oneOfArray")
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

    @Test
    fun `typedTool oneOf schema uses custom discriminator field name`() {
        val tool = tool<DeviceControlRequest, String>("device_control", "设备控制") { params, ctx ->
            "ok"
        }

        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema)

        val eventField = json.jsonObject["properties"]?.jsonObject?.get("event")?.jsonObject
        val oneOfArray = eventField?.get("oneOf")?.jsonArray

        // 验证 discriminator 字段名为 "event" 而非默认的 "type"
        assertTrue(oneOfArray?.any { branch ->
            branch.jsonObject["properties"]?.jsonObject?.containsKey("event") == true
        } == true, "should use 'event' as discriminator field: $oneOfArray")

        // 验证 discriminator 值为 "turned_on" 和 "turned_off"
        val branches = oneOfArray?.map { it.jsonObject } ?: emptyList()
        assertTrue(branches.any { it.toString().contains("turned_on") }, "should have turned_on branch")
        assertTrue(branches.any { it.toString().contains("turned_off") }, "should have turned_off branch")
    }

    // ==================== 嵌套场景测试(覆盖 5 个缺口) ====================

    @Test
    fun `nested data class produces object schema`() {
        val tool = tool<NestedRequest, String>("nested", "嵌套") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val properties = json["properties"]!!.jsonObject

        // 缺口 1: 嵌套 data class 应该是 object,不是 string
        val address = properties["address"]!!.jsonObject
        assertEquals("object", address["type"]!!.jsonPrimitive.content)
        // 嵌套 object 的 properties 也要展开
        assertTrue(address["properties"]!!.jsonObject.containsKey("street"))
        assertTrue(address["properties"]!!.jsonObject.containsKey("city"))
        // 嵌套 object 自己的 required 也要生成
        assertEquals(listOf("street"), address["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `List produces array schema`() {
        val tool = tool<NestedRequest, String>("nested", "嵌套") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val properties = json["properties"]!!.jsonObject

        // 缺口 2: List<T> 应该是 array
        val tags = properties["tags"]!!.jsonObject
        assertEquals("array", tags["type"]!!.jsonPrimitive.content)
        assertEquals("string", tags["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `List of data class produces array of object`() {
        @Serializable
        data class WithList(
            val addresses: List<Address>
        )
        val tool = tool<WithList, String>("with_list", "list of obj") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val addresses = json["properties"]!!.jsonObject["addresses"]!!.jsonObject

        assertEquals("array", addresses["type"]!!.jsonPrimitive.content)
        val items = addresses["items"]!!.jsonObject
        assertEquals("object", items["type"]!!.jsonPrimitive.content)
        // items 内部 properties 也要展开
        assertTrue(items["properties"]!!.jsonObject.containsKey("street"))
    }

    @Test
    fun `nullable field excluded from required at all levels`() {
        val tool = tool<NestedRequest, String>("nested", "嵌套") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val properties = json["properties"]!!.jsonObject

        // 缺口 4: 顶层 required 应该排除 scores(String?/List<Int>? 之类)
        val topRequired = json["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("name" in topRequired, "non-nullable field should be required: $topRequired")
        assertTrue("tags" in topRequired, "List<String> non-nullable should be required: $topRequired")
        assertTrue("scores" !in topRequired, "nullable List should NOT be in required: $topRequired")

        // 嵌套 object 里的 city: String? 也应该被排除
        val address = properties["address"]!!.jsonObject
        val addrRequired = address["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("street" in addrRequired, "street should be required: $addrRequired")
        assertTrue("city" !in addrRequired, "nullable city should NOT be in required: $addrRequired")
    }

    @Test
    fun `oneOf branch has required array`() {
        val tool = tool<MusicControlRequest, String>("music_control", "音乐控制") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val actionField = json["properties"]!!.jsonObject["action"]!!.jsonObject
        val branches = actionField["oneOf"]!!.jsonArray.map { it.jsonObject }

        // 每个分支都应该有 required,包含 discriminator + 非空子类字段
        val playBranch = branches.first { it.toString().contains("\"play\"") }
        val playRequired = playBranch["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("type" in playRequired, "discriminator should be required: $playRequired")
        assertTrue("song" in playRequired, "non-nullable song should be required: $playRequired")
        // Play.artist 是 String? — 不在 required
        assertTrue("artist" !in playRequired, "nullable artist should NOT be in required: $playRequired")
    }

    @Test
    fun `nested sealed class inside data class`() {
        val tool = tool<NotifyRequest, String>("notify", "通知") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val properties = json["properties"]!!.jsonObject

        // 缺口 3: 顶层 sealed 仍然是 oneOf
        val channel = properties["channel"]!!.jsonObject
        assertTrue(channel.containsKey("oneOf"), "channel should be oneOf: $channel")
        assertEquals(2, channel["oneOf"]!!.jsonArray.size)

        // 缺口 3+: List<sealed> 应该是 array of oneOf
        val fallbacks = properties["fallbacks"]!!.jsonObject
        assertEquals("array", fallbacks["type"]!!.jsonPrimitive.content)
        val items = fallbacks["items"]!!.jsonObject
        assertTrue(items.containsKey("oneOf"), "List<sealed> items should be oneOf: $items")
        assertEquals(2, items["oneOf"]!!.jsonArray.size)
    }

    @Test
    fun `description preserved at all levels`() {
        val tool = tool<NotifyRequest, String>("notify", "通知") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema

        // 1) 顶层字段的 description
        assertTrue("渠道" in schema.schema, "channel description should be at outer oneOf: ${schema.schema}")
        // 2) oneOf 分支内部子字段的 description(嵌套层级)
        assertTrue("收件人" in schema.schema, "email branch to description: ${schema.schema}")
        assertTrue("手机号" in schema.schema, "sms branch phone description: ${schema.schema}")
        // 3) description 不能重复
        assertEquals(1, schema.schema.split("渠道").size - 1, "channel description should appear exactly once")
    }

    @Test
    fun `nested object fields keep their descriptions`() {
        @Serializable
        data class WithDescription(
            val user: Address
        )
        val tool = tool<WithDescription, String>("with_desc", "desc") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val userField = json["properties"]!!.jsonObject["user"]!!.jsonObject
        val userProps = userField["properties"]!!.jsonObject

        // 嵌套 object 里的字段 description 必须保留(走 JSON 路径读 description 字段,不止验证字符串包含)
        val streetDesc = userProps["street"]!!.jsonObject["description"]?.jsonPrimitive?.content
        assertEquals("街道", streetDesc, "street description lost: $userField")
        // 嵌套字段也必须在父级 required 里(因为 Address.street 非空)
        val userRequired = userField["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("street" in userRequired, "nested non-nullable field should be required: $userRequired")
    }

    @Test
    fun `Set produces array schema`() {
        @Serializable
        data class WithSet(
            val tags: Set<String>,
            val codes: Set<Int>
        )
        val tool = tool<WithSet, String>("with_set", "set test") { _, _ -> "ok" }
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        val json = Json.parseToJsonElement(schema.schema).jsonObject
        val properties = json["properties"]!!.jsonObject

        // SET 跟 LIST 走同一分支,生成 array schema
        val tags = properties["tags"]!!.jsonObject
        assertEquals("array", tags["type"]!!.jsonPrimitive.content)
        assertEquals("string", tags["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Set<String> 非空 → 进父级 required
        assertTrue("tags" in json["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val codes = properties["codes"]!!.jsonObject
        assertEquals("array", codes["type"]!!.jsonPrimitive.content)
        assertEquals("number", codes["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
