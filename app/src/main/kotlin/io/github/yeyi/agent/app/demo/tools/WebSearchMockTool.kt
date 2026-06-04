package io.github.yeyi.agent.app.demo.tools

import io.github.yeyi.agent.core.tool.Tool
import io.github.yeyi.agent.core.tool.ToolContext
import io.github.yeyi.agent.core.tool.ToolExecutionResult
import io.github.yeyi.agent.core.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 教学 Tool: 异步/IO 耗时。
 * Mock 真实网络搜索,延迟 1.5s 后返回固定结果,演示 Tool 中 suspend 函数的用法。
 *
 * **可复用提示**: 真实实现可以替换为 Ktor 实际 HTTP 调用(`httpClient.get(...)`),
 * 仍然保持 suspend 签名。
 */
class WebSearchMockTool : Tool {
    override val name = "web_search"
    override val description = "搜索互联网内容(本 demo 中为 mock)"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "搜索关键词"
            }
          },
          "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun execute(arguments: JsonElement, ctx: ToolContext): ToolExecutionResult {
        val query = (arguments as JsonObject)["query"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult(content = "ERROR: missing 'query' field", isError = true)
        delay(1500) // 模拟网络耗时
        return ToolExecutionResult(
            content = "Mock search result for '$query':\n" +
                "- Kotlin Coroutines 官方文档 (https://kotlinlang.org/docs/coroutines-overview.html)\n" +
                "- Android Developer Guide: Kotlin coroutines on Android"
        )
    }
}
