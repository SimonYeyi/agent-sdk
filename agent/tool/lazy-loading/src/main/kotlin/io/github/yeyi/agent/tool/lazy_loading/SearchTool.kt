package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toDefinition
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 搜索工具，返回匹配的工具列表供 LLM 选择。
 */
internal class SearchTool(private val registry: LazyToolRegistry) : Tool {
    override val name: String = "search_tool"

    override val description: String = "当没有合适的工具处理用户请求时，使用本工具查询更多可用工具。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "指代消解后的明确的、清晰的用户指令"
                }
            },
            "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val query = arguments.jsonObject["query"]?.jsonPrimitive?.content ?: error("Missing query")
        if (query.isBlank()) error("query cannot be blank")

        val matchedToolNames = retryOnce {
            search(context.agentContext.llmProvider, query)
        }

        val results = registry.all().filter { it.name in matchedToolNames }
        val definitions = results.joinToString("\n") { it.tool.toDefinition().toString() }
        val text = "查询到以下工具（通过 tool_caller 调用）:\n$definitions"
        return ToolExecutionResult.success(text)
    }

    private inline fun <T> retryOnce(block: () -> T): T {
        return runCatching(block)
            .getOrElse { runCatching(block).getOrElse { error("工具搜索失败，如果有需要，请自行重试") } }
    }

    private suspend fun search(llm: LlmProvider, query: String): List<String> {
        val toolsDescriptions = registry.all().joinToString("\n") { lazyTool ->
            "- ${lazyTool.name}: ${lazyTool.description}"
        }

        val systemPrompt = """
            你是一个工具搜索助手，根据用户查询从以下工具列表中选出最相关的工具。
            
            工具列表：
            $toolsDescriptions
            
            回复要求：
            1. 以 JSON 格式回复
            2. 包含 matched_tools 数组，列出所有相关工具名称（按相关度排序，最多5个）
            3. 无相关工具时返回空数组
            
            回复格式：{"matched_tools": ["tool_name1", "tool_name2"]}
        """.trimIndent()

        val request = ChatRequest(
            messages = listOf(
                ChatMessage.System(systemPrompt),
                ChatMessage.User(listOf(ContentPart.Text(query)))
            ),
            temperature = 0.0,
        )

        val content = llm.chat(request).message.content
        val json = Json.parseToJsonElement(content!!).jsonObject
        val array = json["matched_tools"]?.jsonArray!!
        return array.map { it.jsonPrimitive.content }
    }
}
