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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 搜索工具，返回匹配的工具列表供 LLM 选择。
 */
internal class SearchTool(private val registry: LazyToolRegistry) : Tool {
    override val name: String = NAME

    override val description: String =
        "当没有合适的工具处理用户请求时，使用本工具查询更多可用工具。" +
            "支持一次传入多个查询批量搜索（如同时查天气和新闻），不要为多个查询多次调用本工具。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "queries": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "指代消解后的明确的、清晰的任务指令列表，可一次传入多个互不相关的查询，一次性搜索所有可用工具"
                }
            },
            "required": ["queries"]
        }
        """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val queries = arguments.jsonObject["queries"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?: error("Missing queries")
        if (queries.isEmpty()) error("queries cannot be empty")

        val matchedToolNames = retryOnce {
            search(context.agentContext.llmProvider, queries)
        }

        val results = registry.all().filter { it.name in matchedToolNames }
        if (results.isEmpty()) {
            return ToolExecutionResult.success("未找到相关的工具。")
        }
        val definitions = results.joinToString("\n") { it.tool.toDefinition().toString() }
        val text = "查询到以下工具（通过 ${DynamicToolCaller.NAME} 调用）:\n$definitions"
        return ToolExecutionResult.success(text)
    }

    private inline fun <T> retryOnce(block: () -> T): T {
        return runCatching(block)
            .getOrElse { runCatching(block).getOrElse { error("工具搜索失败，如果有需要，请自行重试") } }
    }

    private suspend fun search(llm: LlmProvider, queries: List<String>): List<String> {
        val toolsDescriptions = registry.all().joinToString("\n") { lazyTool ->
            "- ${lazyTool.name}: ${lazyTool.description}"
        }

        val systemPrompt = """
            你是一个工具搜索助手，根据多个用户查询快速从以下工具列表中选出所有相关的工具。

            工具列表：
            $toolsDescriptions

            回复要求：
            1. 以 JSON 格式回复
            2. 包含 matched_tools 数组，列出与任意查询相关的全部工具名称（并集，按相关度排序，最多10个）
            3. 无相关工具时返回空数组

            回复格式：{"matched_tools": ["tool_name1", "tool_name2"]}
        """.trimIndent()

        val userMessage = queries.joinToString("\n") { "- $it" }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage.System(systemPrompt),
                ChatMessage.User(listOf(ContentPart.Text(userMessage)))
            ),
            temperature = 0.0,
            thinkingEnabled = false,
        )

        val content = llm.chat(request).message.content
        val json = Json.parseToJsonElement(content!!).jsonObject
        val array = json["matched_tools"]?.jsonArray!!
        return array.map { it.jsonPrimitive.content }
    }

    internal companion object {
        const val NAME = "search_tool"
    }
}
