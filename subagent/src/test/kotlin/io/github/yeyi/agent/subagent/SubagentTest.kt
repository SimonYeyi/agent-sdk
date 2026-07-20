package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubagentTest {

    private class StubLlmProvider(
        private val responses: List<ChatResponse> = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    ) : LlmProvider {
        override val name: String = "stub"
        val chatRequests: MutableList<ChatRequest> = mutableListOf()
        private var index = 0

        override suspend fun chat(request: ChatRequest): ChatResponse {
            chatRequests += request
            check(index < responses.size) {
                "StubLlmProvider: chat() called ${index + 1} times, but only ${responses.size} responses scripted"
            }
            return responses[index++]
        }

        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
    }

    private class StubSubagent(
        override val name: String,
        override val description: String = "stub subagent",
        override val maxIterations: Int = 5,
        override val memory: Memory? = null,
        override val tools: List<Tool>? = null,
        private val instructions: String = "stub instructions",
    ) : Subagent {
        var loadCallCount: Int = 0
        override suspend fun load(): String {
            loadCallCount++
            return instructions
        }
    }

    private class StubTool(
        override val name: String,
        override val description: String = "stub tool",
        override val parametersSchema: ToolParameters = ToolParameters.Empty,
    ) : Tool {
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult("ok")
    }

    private fun chatResponse(content: String): ChatResponse =
        ChatResponse(ChatMessage.Assistant(content = content), finishReason = FinishReason.Stop)

    private fun stubAgentContext(
        llm: LlmProvider,
        tools: List<Tool> = emptyList(),
        maxRounds: Int = 20,
        maxIterations: Int = 100,
        memory: Memory = InMemoryMemory(),
    ): AgentContext = AgentContext(
        persona = Persona("main"),
        maxIterations = maxIterations,
        currentIteration = 1,
        memory = memory,
        llmProvider = llm,
        tools = tools,
        maxRounds = maxRounds,
    )

    // ---------- SubagentRegistry ----------

    @Test
    fun `registry capabilityName is Subagent NAME`() {
        val r = SubagentRegistry()
        assertEquals(Subagent.CAPABILITY_TYPE, r.capabilityType)
        assertEquals("subagent", r.capabilityType)
    }

    @Test
    fun `registry stores registered subagents and exposes them via all`() {
        val r = SubagentRegistry()
        val a = StubSubagent("alpha")
        val b = StubSubagent("beta")
        r.register(a)
        r.register(b)
        val names = r.all().map { it.name }.toSet()
        assertEquals(setOf("alpha", "beta"), names)
        assertEquals(2, r.all().size)
    }

    @Test
    fun `registry rejects duplicate subagent names`() {
        val r = SubagentRegistry()
        r.register(StubSubagent("dup"))
        assertFailsWith<IllegalArgumentException> {
            r.register(StubSubagent("dup"))
        }
    }

    @Test
    fun `registry register iterable accepts a collection of subagents`() {
        val r = SubagentRegistry()
        r.register(listOf(StubSubagent("a"), StubSubagent("b")))
        assertEquals(2, r.all().size)
    }

    // ---------- SubagentArguments ----------

    @Test
    fun `arguments schema declares task property and marks it required`() {
        val args = SubagentArguments()
        val parsed = Json.parseToJsonElement(args.schema) as JsonObject
        val properties = parsed["properties"] as JsonObject
        assertNotNull(properties["task"], "schema should expose task property")
        val required = parsed["required"] as JsonArray
        val requiredKeys = required.map { it.toString().trim('"') }.toSet()
        assertTrue(requiredKeys.contains("task"), "task must be in required list")
    }

    @Test
    fun `arguments serializer round-trips a SubagentTask via JSON`() {
        val args = SubagentArguments()
        val original = SubagentTask("write a poem about recursion")
        val element: JsonElement = Json.encodeToJsonElement(args.serializer, original)
        val restored: SubagentTask = Json.decodeFromJsonElement(args.serializer, element)
        assertEquals(original, restored)
    }

    // ---------- SubagentContextFactory ----------

    @Test
    fun `context factory wraps the agentContext of the supplied ToolContext`() {
        val factory = SubagentContextFactory()
        val ac = stubAgentContext(StubLlmProvider())
        val toolCtx = ToolContext(toolCallId = "tc-1", agentContext = ac)
        val sc = factory.create(toolCtx)
        assertSame(ac, sc.agentContext)
    }

    // ---------- Subagent.activate ----------

    @Test
    fun `activate throws IllegalArgumentException when arguments is null`() = runTest {
        val sub = StubSubagent("alpha")
        val ctx = SubagentContext(stubAgentContext(StubLlmProvider()))
        val ex = assertFailsWith<IllegalArgumentException> {
            sub.activate(null, ctx)
        }
        assertTrue(ex.message?.contains("task") == true, "exception should mention 'task'")
    }

    @Test
    fun `activate runs a sub-agent and returns the LLM response content`() = runTest {
        val llm = StubLlmProvider(listOf(chatResponse("all done")))
        val sub = StubSubagent("coder")
        val ctx = SubagentContext(stubAgentContext(llm))
        val result = sub.activate(SubagentTask("do something"), ctx)
        assertEquals("all done", result)
        assertEquals(1, llm.chatRequests.size, "sub-agent must invoke LLM exactly once")
    }

    @Test
    fun `activate forwards the task to the sub-agent's LLM as a user message`() = runTest {
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder")
        val ctx = SubagentContext(stubAgentContext(llm))
        sub.activate(SubagentTask("specific task text"), ctx)
        val messages = llm.chatRequests.single().messages
        val userMessages = messages.filterIsInstance<ChatMessage.User>().map { it.content }
        assertTrue(
            userMessages.contains("specific task text"),
            "sub-agent must send the task as a user message; got=$userMessages"
        )
    }

    @Test
    fun `activate calls load exactly once`() = runTest {
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder")
        val ctx = SubagentContext(stubAgentContext(llm))
        sub.activate(SubagentTask("x"), ctx)
        assertEquals(1, sub.loadCallCount)
    }

    @Test
    fun `activate uses explicit tools minus subagent-named ones when subagent tools is non-empty`() = runTest {
        val explicitKeep = StubTool("keep_me")
        val explicitDrop = StubTool("subagent_something")
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder", tools = listOf(explicitKeep, explicitDrop))
        val ctx = SubagentContext(stubAgentContext(llm, tools = listOf(
            StubTool("from_main_only"),
        )))
        sub.activate(SubagentTask("x"), ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertEquals(setOf("keep_me"), visible)
    }

    @Test
    fun `activate filters out subagent-named tools from main set when subagent tools is null`() = runTest {
        val keep = StubTool("keep_me")
        val dispatchedSubagentA = StubTool("subagent_dispatch")
        val dispatchedSubagentB = StubTool("load_subagent")
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder")  // tools = null → falls back to filtered main set
        val ctx = SubagentContext(stubAgentContext(llm, tools = listOf(
            keep, dispatchedSubagentA, dispatchedSubagentB,
        )))
        sub.activate(SubagentTask("x"), ctx)
        val visible = llm.chatRequests.single().tools.map { it.name }.toSet()
        assertTrue(visible.contains("keep_me"))
        assertFalse(visible.contains("subagent_dispatch"), "must filter by Subagent.CAPABILITY_TYPE containment")
        assertFalse(visible.contains("load_subagent"), "must filter by Subagent.CAPABILITY_TYPE containment")
    }

    @Test
    fun `activate uses the supplied memory instance when provided`() = runTest {
        val sharedMemory = InMemoryMemory()
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder", memory = sharedMemory)
        val ctx = SubagentContext(stubAgentContext(llm))
        sub.activate(SubagentTask("x"), ctx)
        // The shared memory instance must end up holding the sub-agent's user message
        // (i.e., it's wired through, not replaced with a fresh InMemoryMemory).
        // runTest collects messages synchronously by the time the agent returns.
        val messages = sharedMemory.history()
        assertTrue(
            messages.any { it is ChatMessage.User && it.content == "x" },
            "sub-agent must use the supplied memory (got ${messages.size} messages, contents=${messages.map { it::class.simpleName }})"
        )
    }

    @Test
    fun `activate falls back to a fresh InMemoryMemory when subagent memory is null`() = runTest {
        val llm = StubLlmProvider()
        val sub = StubSubagent("coder", memory = null)
        val ctx = SubagentContext(stubAgentContext(llm))
        val result = sub.activate(SubagentTask("hi"), ctx)
        assertEquals("ok", result)
    }
}
