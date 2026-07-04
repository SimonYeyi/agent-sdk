package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.tool.serialization.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class EmailRequest(
    val to: String,
    val subject: String,
    val body: String? = null
)

@Serializable
data class SendEmailResult(
    val messageId: String,
    val sentAt: String
)

class CompressibleToolTestImpl : CompressibleTool<EmailRequest, SendEmailResult>(
    parameterType = TypeToken<EmailRequest>(),
    resultType = TypeToken<SendEmailResult>()
) {
    override val name: String = "send_email"
    override val description: String = "发送邮件"

    override suspend fun execute(parameters: EmailRequest, context: ToolContext): SendEmailResult {
        assertEquals("x@x.com", parameters.to)
        assertEquals("hello", parameters.subject)
        return SendEmailResult("msg-123", "2024-01-01")
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
            override suspend fun chat(request: ChatRequest): io.github.yeyi.agent.llm.ChatResponse =
                throw UnsupportedOperationException()
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flowOf()
        },
        tools = emptyList(),
        maxRounds = 10
    )
)

class CompressibleToolTest {

    @Test
    fun `parametersSchema contains execution`() {
        val tool = CompressibleToolTestImpl()
        val schema = tool.parametersSchema as ToolParameters.JsonSchema
        assertTrue(schema.schema.contains("execution"), "schema should contain 'execution'")
        assertTrue(schema.schema.contains("send_email"), "schema should contain 'send_email'")
    }

    @Test
    fun `execute parses execution string`() = runTest {
        val tool = CompressibleToolTestImpl()
        tool.parametersSchema // 触发 schema 生成

        val json = Json.parseToJsonElement("""{"execution":"send_email(to='x@x.com', subject='hello')"}""")
        val result = tool.execute(json, createToolContext())

        assertEquals(false, result.isError)
        assertTrue(result.content.contains("msg-123"))
    }
}
