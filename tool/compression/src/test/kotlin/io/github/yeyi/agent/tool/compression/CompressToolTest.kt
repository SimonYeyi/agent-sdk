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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
