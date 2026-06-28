package io.gateway.platform.feishu

import com.google.gson.Gson
import io.gateway.model.OutgoingContent
import io.gateway.util.gatewayLog

internal class FeishuMessageFormatter(
    private val gson: Gson = Gson(),
    private val maxMessageLength: Int = 30000
) {
    private val log = gatewayLog("FeishuMessageFormatter")

    internal fun extractTextFromContent(content: OutgoingContent): String {
        return when (content) {
            is OutgoingContent.Text -> content.text
            is OutgoingContent.Image -> "[Image]"
            is OutgoingContent.Audio -> "[Audio]"
            is OutgoingContent.Document -> content.fileName
        }
    }

    internal fun formatOutgoingMessage(content: String): Pair<String, String> {
        val isMarkdown = containsMarkdown(content)
        return if (isMarkdown) {
            "post" to buildMarkdownPostContent(content)
        } else {
            val escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            "text" to """{"text":"$escaped"}"""
        }
    }

    internal fun containsMarkdown(content: String): Boolean {
        val markdownPatterns = listOf(
            Regex("^#{1,6}\\s", RegexOption.MULTILINE),
            Regex("^\\s*[-*]\\s", RegexOption.MULTILINE),
            Regex("```"),
            Regex("`[^`]+`"),
            Regex("\\*\\*[^*]+"),
            Regex("~~[^~]+"),
            Regex("\\[[^\\]]+\\]\\([^)]+\\)"),
            Regex("^>\\s", RegexOption.MULTILINE)
        )
        return markdownPatterns.any { it.containsMatchIn(content) }
    }

    internal fun buildMarkdownPostContent(content: String): String {
        val rows = mutableListOf<List<Map<String, String>>>()
        val lines = content.split("\n")

        for (line in lines) {
            val tag = if (containsInlineMarkdown(line)) "md" else "text"
            rows.add(listOf(mapOf("tag" to tag, "text" to line)))
        }

        val outer = mutableMapOf<String, Any>()
        outer["zh_cn"] = mapOf("title" to "", "content" to rows)

        return gson.toJson(outer)
    }

    internal fun containsInlineMarkdown(line: String): Boolean {
        val listPatterns = listOf(
            Regex("^\\s*[-*+]\\s"),
            Regex("^\\s*\\d+\\.\\s"),
            Regex("^\\s*#+\\s"),
        )
        if (listPatterns.any { it.containsMatchIn(line) }) return true

        val inlinePatterns = listOf(
            Regex("```"),
            Regex("`[^`]+`"),
            Regex("\\*\\*[^*]+"),
            Regex("\\*[^*]+\\*"),
            Regex("~~[^~]+"),
            Regex("\\[.+?]\\(.+?\\)")
        )
        return inlinePatterns.any { it.containsMatchIn(line) }
    }

    internal fun splitMessage(text: String): List<String> {
        if (text.length <= maxMessageLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxMessageLength) {
                chunks.add(remaining)
                break
            }

            val splitAt = remaining.lastIndexOf('\n', maxMessageLength)
            if (splitAt > maxMessageLength / 2) {
                chunks.add(remaining.substring(0, splitAt))
                remaining = remaining.substring(splitAt + 1)
            } else {
                val spaceAt = remaining.lastIndexOf(' ', maxMessageLength)
                if (spaceAt > maxMessageLength / 2) {
                    chunks.add(remaining.substring(0, spaceAt))
                    remaining = remaining.substring(spaceAt + 1)
                } else {
                    chunks.add(remaining.substring(0, maxMessageLength))
                    remaining = remaining.substring(maxMessageLength)
                }
            }
        }

        return chunks
    }
}