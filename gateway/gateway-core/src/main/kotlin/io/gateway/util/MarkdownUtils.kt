package io.gateway.util

object MarkdownUtils {

    fun toPlainText(markdown: String): String {
        var result = markdown

        result = result.replace(Regex("""^#+\s+""", RegexOption.MULTILINE), "")
        result = result.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        result = result.replace(Regex("""\*(.+?)\*"""), "$1")
        result = result.replace(Regex("""__(.+?)__"""), "$1")
        result = result.replace(Regex("""_(.+?)_"""), "$1")
        result = result.replace(Regex("""`(.+?)`"""), "$1")
        result = result.replace(Regex("""```[\s\S]*?```"""), "")
        result = result.replace(Regex("""\[(.+?)\]\(.+?\)"""), "$1")
        result = result.replace(Regex("""^>\s+""", RegexOption.MULTILINE), "")
        result = result.replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "• ")
        result = result.replace(Regex("""^\d+\.\s+""", RegexOption.MULTILINE), "")
        result = result.replace(Regex("""---+"""), "")
        result = result.replace("\n\n\n", "\n\n")

        return result.trim()
    }

    fun truncate(text: String, maxLength: Int = 2000): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength - 3) + "..."
    }
}
