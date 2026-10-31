package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchToolTest {

    private class FixedTool(
        override val name: String,
        override val description: String = "test tool",
    ) : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    private fun createStubContext(llmProvider: LlmProvider): ToolExecutionContext {
        return ToolExecutionContext(
            toolCallId = "test",
            agentContext = AgentContext(
                persona = AgentPersona("test"),
                maxIterations = 10,
                currentIteration = 1,
                memory = InMemoryMemory(),
                llmProvider = llmProvider,
                tools = emptyList(),
                maxRounds = 20,
            )
        )
    }

    private fun fakeLlmProvider(responseContent: String) = object : LlmProvider {
        override val name: String = "fake"
        override suspend fun chat(request: ChatRequest): ChatResponse {
            return ChatResponse(
                message = ChatMessage.Assistant(content = responseContent, toolCalls = emptyList()),
                usage = null,
                finishReason = FinishReason.Stop,
            )
        }
        override fun chatStream(request: ChatRequest) = error("not implemented")
    }

    @Test
    fun `search_tool returns matched tools via LLM`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))
        registry.register(LazyTool(FixedTool("map", "地图导航")))

        val llmProvider = fakeLlmProvider("""{"matched_tools": ["weather", "map"]}""")
        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("queries", buildJsonArray { add(JsonPrimitive("我想查天气和地图")) }) },
            context = createStubContext(llmProvider)
        )

        assertEquals(false, result.isError)
        val text = result.parts.text
        assertTrue(text.contains("weather"), "should contain weather: $text")
        assertTrue(text.contains("map"), "should contain map: $text")
        assertTrue("news" !in text, "should NOT contain news: $text")
    }

    @Test
    fun `search_tool returns no-tool message when LLM finds no match`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))

        val llmProvider = fakeLlmProvider("""{"matched_tools": []}""")
        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("queries", buildJsonArray { add(JsonPrimitive("不相关的查询")) }) },
            context = createStubContext(llmProvider)
        )

        assertEquals(false, result.isError)
        val text = result.parts.text
        assertTrue(text.contains("未找到"), "should indicate no tools found: $text")
    }

    @Test
    fun `search_tool batches multiple queries into one LLM call`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))
        registry.register(LazyTool(FixedTool("map", "地图导航")))

        var llmCalls = 0
        var receivedMessage = ""
        val llmProvider = object : LlmProvider {
            override val name: String = "fake"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                llmCalls++
                receivedMessage = (request.messages.last() as ChatMessage.User).parts
                    .filterIsInstance<ContentPart.Text>().joinToString { it.text }
                return ChatResponse(
                    message = ChatMessage.Assistant(content = """{"matched_tools": ["weather", "news"]}""", toolCalls = emptyList()),
                    usage = null,
                    finishReason = FinishReason.Stop,
                )
            }
            override fun chatStream(request: ChatRequest) = error("not implemented")
        }
        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("queries", buildJsonArray { add(JsonPrimitive("查天气")); add(JsonPrimitive("搜新闻")) }) },
            context = createStubContext(llmProvider)
        )

        assertEquals(false, result.isError)
        assertEquals(1, llmCalls, "multiple queries should be searched in a single LLM call")
        assertTrue(receivedMessage.contains("查天气"), "user message should include first query: $receivedMessage")
        assertTrue(receivedMessage.contains("搜新闻"), "user message should include second query: $receivedMessage")
        val text = result.parts.text
        assertTrue(text.contains("weather"), "should contain weather: $text")
        assertTrue(text.contains("news"), "should contain news: $text")
    }

    @Test
    fun `search_tool throws when LLM returns invalid JSON after retry`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))

        val llmProvider = fakeLlmProvider("这不是有效的JSON")
        val searchTool = SearchTool(registry)

        var threw = false
        try {
            searchTool.execute(
                arguments = buildJsonObject { put("queries", buildJsonArray { add(JsonPrimitive("天气")) }) },
                context = createStubContext(llmProvider)
            )
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "should throw IllegalStateException after retry fails")
    }

    @Test
    fun `search_tool name is search_tool`() {
        val registry = LazyToolRegistry()
        val searchTool = SearchTool(registry)
        assertEquals("search_tool", searchTool.name)
    }

    @Test
    fun `search_tool description is meaningful`() {
        val registry = LazyToolRegistry()
        val searchTool = SearchTool(registry)
        assertTrue(searchTool.description.isNotEmpty(), "description should not be empty")
        assertTrue(searchTool.description.contains("工具"), "description should mention tools")
    }

    @Test
    fun `search_tool throws on missing queries`() = runTest {
        val registry = LazyToolRegistry()
        val llmProvider = fakeLlmProvider("{}")
        val searchTool = SearchTool(registry)

        var threw = false
        try {
            searchTool.execute(
                arguments = buildJsonObject { },
                context = createStubContext(llmProvider)
            )
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message!!.contains("Missing queries"))
        }
        assertTrue(threw, "should throw IllegalStateException on missing queries")
    }
}
