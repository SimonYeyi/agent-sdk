package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 搜索工具，返回匹配的工具列表供 LLM 选择。
 */
internal class SearchTool(private val registry: LazyToolRegistry) : Tool {
    override val name: String = "search_tool"

    override val description: String = ""

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

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val query = arguments.jsonObject["query"]?.jsonPrimitive?.content ?: ""
        // TODO: Use model-based semantic search instead of keyword match
        val results = registry.all().filter {
            it.description.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true)
        }
        val definitions = results.joinToString("\n") { it.tool.toDefinition().toString() }
        val text = "search_tool 返回以下工具（通过 tool_caller 调用）:\n$definitions"
        return ToolExecutionResult.success(text)
    }
}
