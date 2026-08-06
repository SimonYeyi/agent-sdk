package io.github.yeyi.agent.demo.agent.demo.tools

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 网络搜索 Tool
 * 使用 Bing 搜索获取真实结果。
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "搜索互联网内容，使用 Bing 搜索"

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

    private val httpClient = HttpClient(CIO)

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val query = (arguments as JsonObject)["query"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult(content = "ERROR: missing 'query' field", isError = true)

        return try {
            val encodedQuery = withContext(Dispatchers.IO) { URLEncoder.encode(query, "UTF-8") }
            val url = "https://www.bing.com/search?q=$encodedQuery"
            val html = withContext(Dispatchers.IO) {
                val response: HttpResponse = httpClient.get(url) {
                    headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    headers.append(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    headers.append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
                }
                response.bodyAsText()
            }
            val results = parseBingResults(html)
            val content = if (results.isNotEmpty()) {
                results.joinToString("\n")
            } else {
                "未找到与「$query」相关的搜索结果"
            }
            ToolExecutionResult(content = content)
        } catch (e: Exception) {
            ToolExecutionResult(content = "搜索「$query」失败：${e.message}", isError = true)
        }
    }

    private fun parseBingResults(html: String): List<String> {
        val results = mutableListOf<String>()

        // Bing 结果结构：
        // <li class="b_algo"><h2><a href="url">标题</a></h2><p>摘要...</p></li>
        val resultPattern = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        val linkPattern = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippetPattern = Regex("<p[^>]*>([^<]+)</p>", RegexOption.DOT_MATCHES_ALL)

        val resultBlocks = resultPattern.findAll(html).take(10).toList()

        for (block in resultBlocks) {
            val blockHtml = block.groupValues[1]
            val links = linkPattern.findAll(blockHtml).toList()
            val snippets = snippetPattern.findAll(blockHtml).toList()

            if (links.isNotEmpty()) {
                val link = links.first().groupValues[1].trim()
                val title = links.first().groupValues[2]
                    .replace("<em>", "").replace("</em>", "")
                    .replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .trim()
                val snippetMatch = snippets.firstOrNull()
                val snippet = if (snippetMatch != null && snippetMatch.groupValues.size > 1) {
                    snippetMatch.groupValues[1]
                        .replace("<em>", "").replace("</em>", "")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .trim()
                } else {
                    ""
                }

                if (title.isNotEmpty() && link.startsWith("http") && !link.contains("bing.com")) {
                    results.add("$title\n$snippet\n来源：$link")
                }
            }
        }

        return results.take(10)
    }
}