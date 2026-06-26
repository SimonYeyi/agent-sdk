package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CapabilityAdapterTest {

    @Serializable
    private data class EchoArgs(val message: String)

    private class EchoContext : CapabilityContext

    private class EchoContextFactory(
        private val payload: EchoContext = EchoContext(),
    ) : CapabilityContextFactory<EchoContext> {
        var createCount: Int = 0
        override fun create(context: ToolContext): EchoContext {
            createCount++
            return payload
        }
    }

    private class EchoCapability(
        override val name: String,
        override val description: String,
    ) : Capability<EchoArgs, EchoContext> {
        var lastArgs: EchoArgs? = null
        var lastContext: EchoContext? = null
        var callCount: Int = 0
        override suspend fun activate(arguments: EchoArgs?, context: EchoContext): String {
            callCount++
            lastArgs = arguments
            lastContext = context
            return "ok:$name:${arguments?.message ?: "<null>"}"
        }
    }

    private fun echoArgs(): CapabilityArguments<EchoArgs> = object : CapabilityArguments<EchoArgs> {
        override val schema: String =
            """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}"""
        override val serializer: KSerializer<EchoArgs> = EchoArgs.serializer()
    }

    private fun stubLlm(): LlmProvider = object : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in capability tests")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("LlmProvider.chatStream must not be called in capability tests")))
    }

    private fun stubToolContext(): ToolContext = ToolContext(
        toolCallId = "tc-1",
        agentContext = AgentContext(
            persona = Persona("test"),
            maxIterations = 10,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = stubLlm(),
            tools = emptyList(),
            maxRounds = 20,
        )
    )

    private fun parseSchema(schema: ToolParameters): JsonObject =
        when (schema) {
            is ToolParameters.JsonSchema -> Json.parseToJsonElement(schema.schema).let { it as JsonObject }
            ToolParameters.Empty -> JsonObject(emptyMap())
        }

    private fun CapabilityAdapter<*, *, *>.producedTools(): List<Tool> {
        val m = CapabilityAdapter::class.java.getDeclaredMethod("adapt").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(this) as List<Tool>
    }

    // ---------- DefaultCapabilityRegistry ----------

    @Test
    fun `register adds a capability to the registry`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cap")
        r.register(EchoCapability("a", "A"))
        assertEquals(1, r.all().size)
    }

    @Test
    fun `all returns every registered capability`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cap")
        r.register(EchoCapability("a", "A"))
        r.register(EchoCapability("b", "B"))
        r.register(EchoCapability("c", "C"))
        val names = r.all().map { it.name }.toSet()
        assertEquals(setOf("a", "b", "c"), names)
        assertEquals(3, r.all().size)
    }

    @Test
    fun `register iterable registers each capability`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cap")
        r.register(listOf(EchoCapability("a", "A"), EchoCapability("b", "B")))
        assertEquals(2, r.all().size)
        assertEquals(setOf("a", "b"), r.all().map { it.name }.toSet())
    }

    @Test
    fun `registering duplicate capability name throws`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cap")
        r.register(EchoCapability("dup", "first"))
        assertFailsWith<IllegalArgumentException> {
            r.register(EchoCapability("dup", "second"))
        }
    }

    // ---------- enableDelegateAdaptMode = false ----------

    @Test
    fun `OneToOne produces one tool per registered capability`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("a", "A"))
        r.register(EchoCapability("b", "B"))
        val adapter = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = false)
        assertEquals(2, adapter.producedTools().size)
    }

    @Test
    fun `OneToOne tool name follows capabilityName underscore capability name pattern`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("mycat")
        r.register(EchoCapability("alpha", "alpha desc"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = false)
            .producedTools().single()
        assertEquals("mycat_alpha", tool.name)
    }

    @Test
    fun `OneToOne tool description comes from the underlying capability`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "this is alpha"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = false)
            .producedTools().single()
        assertEquals("this is alpha", tool.description)
    }

    @Test
    fun `OneToOne tool parametersSchema wraps arguments schema when arguments is provided`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = false)
            .producedTools().single()
        val schema = assertIs<ToolParameters.JsonSchema>(tool.parametersSchema)
        val parsed = parseSchema(schema)
        assertEquals("object", parsed["type"]?.toString()?.trim('"'))
        assertNotNull(parsed["properties"], "schema should expose properties")
    }

    @Test
    fun `OneToOne tool parametersSchema is Empty when arguments is null`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), null, enableDelegateAdaptMode = false)
            .producedTools().single()
        assertSame(ToolParameters.Empty, tool.parametersSchema)
    }

    @Test
    fun `OneToOne tool execute decodes entire arguments and routes to capability`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        val cap = EchoCapability("alpha", "alpha")
        r.register(cap)
        val factory = EchoContextFactory()
        val tool = CapabilityAdapter.of(r, factory, echoArgs(), enableDelegateAdaptMode = false)
            .producedTools().single()

        val result = tool.execute(
            arguments = buildJsonObject { put("message", "hello") },
            context = stubToolContext(),
        )

        assertEquals("ok:alpha:hello", result.content)
        assertEquals(false, result.isError)
        assertEquals(1, cap.callCount)
        assertEquals(EchoArgs("hello"), cap.lastArgs)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `OneToOne tool execute passes null input when arguments is null`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        val cap = EchoCapability("alpha", "alpha")
        r.register(cap)
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), null, enableDelegateAdaptMode = false)
            .producedTools().single()

        val result = tool.execute(
            arguments = buildJsonObject { },
            context = stubToolContext(),
        )

        assertEquals("ok:alpha:<null>", result.content)
        assertNull(cap.lastArgs)
    }

    // ---------- enableDelegateAdaptMode = true ----------

    @Test
    fun `Delegate produces a single tool named load capabilityName`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("mycat")
        r.register(EchoCapability("a", "A"))
        r.register(EchoCapability("b", "B"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = true)
            .producedTools().single()
        assertEquals("load_mycat", tool.name)
    }

    @Test
    fun `Delegate tool description enumerates every registered capability name and description`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha desc"))
        r.register(EchoCapability("beta", "beta desc"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = true)
            .producedTools().single()
        assertTrue(tool.description.contains("alpha"), "description should list alpha")
        assertTrue(tool.description.contains("alpha desc"), "description should list alpha desc")
        assertTrue(tool.description.contains("beta"), "description should list beta")
        assertTrue(tool.description.contains("beta desc"), "description should list beta desc")
    }

    @Test
    fun `Delegate tool parametersSchema has routing key required and nested arguments when provided`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = true)
            .producedTools().single()
        val schema = assertIs<ToolParameters.JsonSchema>(tool.parametersSchema)
        val parsed = parseSchema(schema)
        assertEquals("object", parsed["type"]?.toString()?.trim('"'))
        val properties = parsed["properties"] as JsonObject
        assertNotNull(properties["cat_name"], "routing field cat_name should be in properties")
        assertNotNull(properties["arguments"], "nested arguments should be in properties")
        val required = parsed["required"] as kotlinx.serialization.json.JsonArray
        val requiredKeys = required.map { it.toString().trim('"') }.toSet()
        assertTrue(requiredKeys.contains("cat_name"))
        assertTrue(requiredKeys.contains("arguments"))
    }

    @Test
    fun `Delegate tool parametersSchema omits arguments when arguments is null`() {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tool = CapabilityAdapter.of(r, EchoContextFactory(), null, enableDelegateAdaptMode = true)
            .producedTools().single()
        val schema = assertIs<ToolParameters.JsonSchema>(tool.parametersSchema)
        val parsed = parseSchema(schema)
        val properties = parsed["properties"] as JsonObject
        assertNotNull(properties["cat_name"])
        assertNull(properties["arguments"], "no arguments field when no arguments supplied")
        val required = parsed["required"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, required.size, "only the routing key should be required")
    }

    @Test
    fun `Delegate tool execute routes to the named capability and decodes nested arguments`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        val alpha = EchoCapability("alpha", "alpha")
        val beta = EchoCapability("beta", "beta")
        r.register(alpha)
        r.register(beta)
        val factory = EchoContextFactory()
        val tools: List<Tool> = CapabilityAdapter.of(r, factory, echoArgs(), enableDelegateAdaptMode = true)
            .producedTools()
        val tool = tools.single()

        val result = tool.execute(
            arguments = buildJsonObject {
                put("cat_name", "beta")
                put("arguments", buildJsonObject { put("message", "hi") })
            },
            context = stubToolContext(),
        )

        assertEquals("ok:beta:hi", result.content)
        assertEquals(false, result.isError)
        assertEquals(1, beta.callCount)
        assertEquals(0, alpha.callCount, "alpha must not be invoked")
        assertEquals(EchoArgs("hi"), beta.lastArgs)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun `Delegate tool execute passes null input when arguments is null`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        val cap = EchoCapability("alpha", "alpha")
        r.register(cap)
        val tools: List<Tool> = CapabilityAdapter.of(r, EchoContextFactory(), null, enableDelegateAdaptMode = true)
            .producedTools()
        val tool = tools.single()

        val result = tool.execute(
            arguments = buildJsonObject { put("cat_name", "alpha") },
            context = stubToolContext(),
        )

        assertEquals("ok:alpha:<null>", result.content)
        assertEquals(false, result.isError)
        assertNull(cap.lastArgs)
    }

    @Test
    fun `Delegate tool execute returns isError when routing key is missing`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tools: List<Tool> = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = true)
            .producedTools()
        val tool = tools.single()

        val result = tool.execute(
            arguments = buildJsonObject { put("arguments", buildJsonObject { put("message", "x") }) },
            context = stubToolContext(),
        )

        assertEquals(true, result.isError)
        assertTrue(result.content.contains("cat_name"))
    }

    @Test
    fun `Delegate tool execute returns isError when capability name is not found`() = runTest {
        val r = DefaultCapabilityRegistry<EchoContext, Capability<EchoArgs, EchoContext>, EchoArgs>("cat")
        r.register(EchoCapability("alpha", "alpha"))
        val tools: List<Tool> = CapabilityAdapter.of(r, EchoContextFactory(), echoArgs(), enableDelegateAdaptMode = true)
            .producedTools()
        val tool = tools.single()

        val result = tool.execute(
            arguments = buildJsonObject {
                put("cat_name", "ghost")
                put("arguments", buildJsonObject { put("message", "x") })
            },
            context = stubToolContext(),
        )

        assertEquals(true, result.isError)
        assertTrue(result.content.contains("cat_name"))
        assertTrue(result.content.contains("ghost"))
    }
}
