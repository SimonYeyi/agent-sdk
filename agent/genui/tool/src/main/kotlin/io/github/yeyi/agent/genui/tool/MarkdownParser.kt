package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 把 markdown 文本解析为卡片 IR（[TreeNode] 根恒为 `Card`,内部不嵌套 `Card`）。
 *
 * content 不是 markdown 时返回 null——调用方据此回退到 LLM 排版路径。
 * 判定与解析单遍扫描完成,不重复遍历。
 *
 * ## md 判定
 *
 * 任一结构性行命中即判 md（marker 后必须跟空格,排除 `#标签`/`1.先做` 等无空格形态）：
 * - 标题 `^#{1,3} +\S`
 * - 无序列表 `^[-*] +\S`
 * - 有序列表 `^\d+\. +\S`
 * - 引用 `^> +\S`
 * - 分隔线 `^(---|\*\*\*|___)\s*$`
 * - 表格 `^\|.*\|\s*$` 且下一行是分隔行 `^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?$`
 *
 * 行内 `**bold**` 不单独触发判定——`**强调**` 在普通文本里也常见,容易误判。
 *
 * ## md → IR 映射
 *
 * - `# H1`/`## H2`/`### H3` → `Text(style=headlineLarge/headlineMedium/headlineSmall, fontWeight=Bold)`
 * - `- item`/`* item` → 连续同类项合并成 `Column(spacing=4)`,每个 `Text(text="• $item", style=bodyLarge)`
 * - `1. item` → 连续同类项合并成 `Column(spacing=4)`,每个 `Text(text="$n. $item", style=bodyLarge)`（保留原序号）
 * - 段落（连续非空行）→ `Text(style=bodyLarge)`
 * - `> quote` → `Row(background=surfaceVariant, padding=12)` 包 `Text(style=bodyMedium, color=onSurfaceVariant)`（引用块用浅底色圆角块 set apart,不保留 `>` 前缀）
 * - `---` → `Divider()`
 * - 表格 → `Column(spacing=8)` > 多个 `Row(spacing=8)`（表头 + 数据行）> 多个 `Text(weight=1, maxLines=1)`。
 *   表头行 `fontWeight=Bold`,数据行 Normal;每列 Text 绑 `weight=1` 等宽分配 Row 剩余宽度（依赖 Row 内 weight）。
 *
 * ## 加粗（行内 span）
 *
 * 段落/标题/列表项/引用块内部的 `**bold**` 拆成 'TextSpan'——加粗段 `bold=true`,普通段 `bold=false`。
 * Text 节点本身不带 `fontWeight`（避免与 span 冲突）,加粗语义经 `Span` 子节点表达:
 * - 单段（无 `**`）→ 直接 `Text`,无 Span 子节点
 * - 多段 → `Text` 的 `children` 装多个 `Span` 子节点（component="Span", bindings 装 `text` + `bold`）
 *
 * 渲染层把 Text 的 Span children 合并进 `AnnotatedString`——加粗段套 `SpanStyle(fontWeight=Bold)`,
 * 文本自然换行,不会因 Row 横向排列窄屏错乱。
 */
internal fun parseMarkdown(content: String): TreeNode? {
    val lines = content.lines()
    if (!lines.any { it.isStructuralMarkdown() }) return null

    val children = mutableListOf<TreeNode>()
    val listBuffer = mutableListOf<TreeNode>()
    var listType: ListType? = null
    val paragraphBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isEmpty()) return
        val text = paragraphBuffer.joinToString("\n")
        paragraphBuffer.clear()
        children += textNode(text, style = "bodyLarge")
    }

    fun flushList() {
        if (listBuffer.isEmpty()) return
        val items = listBuffer.toList()
        listBuffer.clear()
        listType = null
        children += TreeNode(
            component = "Column",
            bindings = buildJsonObject { put("spacing", JsonPrimitive(4)) },
            children = items,
        )
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // 表格需要预读下一行（分隔行），单行判定无法识别——遇到 |...| 行先看下一行
        val tableBlock = tryParseTable(lines, i)
        if (tableBlock != null) {
            flushParagraph()
            flushList()
            children += tableBlock.node
            i = tableBlock.nextIndex
            continue
        }
        when {
            line.isBlank() -> {
                flushParagraph()
                flushList()
            }

            line.isHr() -> {
                flushParagraph()
                flushList()
                children += TreeNode(component = "Divider")
            }

            line.headingLevel() != null -> {
                flushParagraph()
                flushList()
                val level = line.headingLevel()!!
                val text = line.trimStart().drop(level).trim()  // 剥掉前导 # + 空格
                val style = when (level) {
                    1 -> "headlineLarge"
                    2 -> "headlineMedium"
                    else -> "headlineSmall"
                }
                children += textNode(text, style = style, fontWeight = "Bold")
            }

            line.unorderedItem() != null -> {
                flushParagraph()
                if (listType != ListType.UNORDERED) flushList()
                listType = ListType.UNORDERED
                listBuffer += textNode("• ${line.unorderedItem()!!}", style = "bodyLarge")
            }

            line.orderedItem() != null -> {
                flushParagraph()
                if (listType != ListType.ORDERED) flushList()
                listType = ListType.ORDERED
                val (num, text) = line.orderedItem()!!
                listBuffer += textNode("$num. $text", style = "bodyLarge")
            }

            line.quoteText() != null -> {
                flushParagraph()
                flushList()
                // 引用块：Row 带浅底色圆角块包文本，set apart 而非保留 > 前缀（像控制台输出）
                children += TreeNode(
                    component = "Row",
                    bindings = buildJsonObject {
                        put("background", JsonPrimitive("surfaceVariant"))
                        put("padding", JsonPrimitive(12))
                    },
                    children = listOf(
                        textNode(line.quoteText()!!, style = "bodyMedium", color = "onSurfaceVariant"),
                    ),
                )
            }

            else -> {
                flushList()
                paragraphBuffer += line
            }
        }
        i++
    }
    flushParagraph()
    flushList()

    // spacing=8：md 块本就连贯，default adapter 的 Card 默认 12dp 累加显空旷
    return TreeNode(
        component = "Card",
        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
        children = children,
    )
}

private enum class ListType { UNORDERED, ORDERED }

/** 行是否为结构性 md（标题/列表/引用/分隔线/表格之一,marker 后必须跟空格）。 */
private fun String.isStructuralMarkdown(): Boolean =
    headingLevel() != null || unorderedItem() != null || orderedItem() != null ||
            quoteText() != null || isHr() || isTableRow()

/** `#`/`##`/`###` 标题级别;非标题返回 null。marker 后必须跟空格。 */
private fun String.headingLevel(): Int? {
    val trimmed = trimStart()
    if (!trimmed.startsWith("#")) return null
    val level = trimmed.takeWhile { it == '#' }.length
    if (level !in 1..3) return null
    if (trimmed.length <= level || !trimmed[level].isWhitespace()) return null
    return level
}

/** `---`/`***`/`___` 分隔线（整行只含这三个字符之一）。 */
private fun String.isHr(): Boolean {
    val t = trim()
    if (t.length < 3) return false
    val ch = t.first()
    if (ch != '-' && ch != '*' && ch != '_') return false
    return t.all { it == ch }
}

/** `- item`/`* item` 的项目文本;非无序列表项返回 null。marker 后必须跟空格。 */
private fun String.unorderedItem(): String? {
    val t = trimStart()
    if (t.isEmpty()) return null
    val ch = t.first()
    if (ch != '-' && ch != '*') return null
    if (t.length == 1 || !t[1].isWhitespace()) return null
    val item = t.substring(2).trim()
    return item.ifEmpty { null }
}

/** `1. item` 的 (序号, 文本);非有序列表项返回 null。marker 后必须跟空格。 */
private fun String.orderedItem(): Pair<String, String>? {
    val t = trimStart()
    val dot = t.indexOf('.')
    if (dot <= 0) return null
    val num = t.substring(0, dot)
    if (num.any { !it.isDigit() }) return null
    if (t.length <= dot + 1 || !t[dot + 1].isWhitespace()) return null
    val item = t.substring(dot + 2).trim()
    if (item.isEmpty()) return null
    return num to item
}

/** `> quote` 的引用文本;非引用返回 null。marker 后必须跟空格。 */
private fun String.quoteText(): String? {
    val t = trimStart()
    if (!t.startsWith(">")) return null
    if (t.length == 1 || !t[1].isWhitespace()) return null
    return t.substring(2).trim().ifEmpty { null }
}

/** 表格行：`| cell | cell |` 或 `cell | cell`（首尾 `|` 可省略其一）。 */
private fun String.isTableRow(): Boolean {
    val t = trim()
    if (t.isEmpty() || '|' !in t) return false
    return t.count { it == '|' } >= 1 && !t.startsWith("```")
}

/** 表格分隔行：`|---|---|` / `|:--|:-:|--:|`,`-` 至少一个,`:` 可选,列间用 `|` 分隔。 */
private fun String.isTableDivider(): Boolean {
    val t = trim().removePrefix("|").removeSuffix("|").trim()
    if (t.isEmpty()) return false
    return t.split("|").all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.any { it == '-' }
    }
}

/** 把 `| a | b |` 拆成单元格文本列表（首尾 `|` 可省略其一,空单元格保留为空串）。 */
private fun String.splitTableRow(): List<String> {
    val t = trim().removePrefix("|").removeSuffix("|")
    return t.split("|").map { it.trim() }
}

/**
 * 尝试从 [lines] 的 [startIndex] 起解析一张 GFM 表格。
 *
 * 表格契约：第 0 行是表头（`|...|`），第 1 行是分隔行（`|---|---|`），后续行是数据行。
 * 缺分隔行判失败返回 null（GFM 表格的硬性要求）。
 *
 * 成功返回 (表格根节点, 下一行索引)；失败返回 null,调用方按普通行处理。
 */
private fun tryParseTable(lines: List<String>, startIndex: Int): TableParseResult? {
    if (startIndex + 1 >= lines.size) return null
    val headerLine = lines[startIndex]
    val dividerLine = lines[startIndex + 1]
    if (!headerLine.isTableRow() || !dividerLine.isTableDivider()) return null

    val header = headerLine.splitTableRow()
    val rows = mutableListOf<List<String>>()
    var i = startIndex + 2
    while (i < lines.size && lines[i].isTableRow() && !lines[i].isTableDivider()) {
        rows += lines[i].splitTableRow()
        i++
    }

    val rowNodes = mutableListOf<TreeNode>()
    rowNodes += tableRowNode(header, isHeader = true)
    for (row in rows) {
        rowNodes += tableRowNode(row, isHeader = false, columnCount = header.size)
    }
    val root = TreeNode(
        component = "Column",
        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
        children = rowNodes,
    )
    return TableParseResult(root, i)
}

/** 表格一行的 IR：`Row(spacing=8)` > 多个 `Text(weight=1, maxLines=1)`。表头加 Bold,数据行 Normal。 */
private fun tableRowNode(
    cells: List<String>,
    isHeader: Boolean,
    columnCount: Int = cells.size
): TreeNode {
    val children = (0 until columnCount).map { index ->
        val text = cells.getOrElse(index) { "" }
        val bindings = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("style", JsonPrimitive("bodyMedium"))
            put("weight", JsonPrimitive(1f))
            put("maxLines", JsonPrimitive(1))
            if (isHeader) put("fontWeight", JsonPrimitive("Bold"))
        }
        TreeNode(component = "Text", bindings = bindings)
    }
    return TreeNode(
        component = "Row",
        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
        children = children,
    )
}

private data class TableParseResult(val node: TreeNode, val nextIndex: Int)

/**
 * 构造文本节点:文本里的 `**bold**` 拆成多段——加粗段 bold=true,普通段 bold=false。
 * - `fontWeight != null`（标题等整段统一字重）→ 强制单段,剥 `**`（整段本来就 Bold,内部 `**` 无视觉差异）
 * - 单段（无 `**`）→ 直接 `Text`,无 Span 子节点
 * - 多段 → `Text` 的 children 装多个 `Span` 子节点（加粗段 bold=true）
 *
 * Text 自身不带 fontWeight（避免与 span 冲突）,加粗语义经 Span 表达。
 * 渲染层把 Span children 合并进 AnnotatedString,文本自然换行不因 Row 横向排列错乱。
 */
private fun textNode(
    text: String,
    style: String,
    fontWeight: String? = null,
    color: String? = null
): TreeNode {
    // 整段统一字重（标题）:内部 ** 无意义,剥掉走单段
    if (fontWeight != null) {
        return textNodeOf(text.replace("**", ""), style, fontWeight, color)
    }
    val segments = splitBoldSegments(text)
    if (segments.size == 1) {
        return textNodeOf(segments[0].text, style, if (segments[0].bold) "Bold" else null, color)
    }
    val baseBindings = buildJsonObject {
        put("style", JsonPrimitive(style))
        if (color != null) put("color", JsonPrimitive(color))
    }
    return TreeNode(
        component = "Text",
        bindings = baseBindings,
        children = segments.map { seg -> spanNode(seg.text, seg.bold) },
    )
}

/** 行内 span 子节点:component="Span",bindings 装 text + bold。渲染层合并进 Text 的 AnnotatedString。 */
private fun spanNode(text: String, bold: Boolean): TreeNode {
    val bindings = buildJsonObject {
        put("text", JsonPrimitive(text))
        put("bold", JsonPrimitive(bold))
    }
    return TreeNode(component = "Span", bindings = bindings)
}

/** 拆分 `**bold**` 段:返回 (文本, 是否加粗) 列表,普通段与加粗段交替。 */
private fun splitBoldSegments(text: String): List<Segment> {
    if ("**" !in text) return listOf(Segment(text, bold = false))
    val result = mutableListOf<Segment>()
    var remaining = text
    while (remaining.isNotEmpty()) {
        val start = remaining.indexOf("**")
        if (start < 0) {
            result += Segment(remaining, bold = false)
            break
        }
        if (start > 0) result += Segment(remaining.substring(0, start), bold = false)
        val afterStart = remaining.substring(start + 2)
        val end = afterStart.indexOf("**")
        if (end < 0) {
            // 未闭合的 `**`:当作普通文本
            result += Segment("**" + afterStart, bold = false)
            break
        }
        result += Segment(afterStart.substring(0, end), bold = true)
        remaining = afterStart.substring(end + 2)
    }
    return result.filter { it.text.isNotEmpty() }
}

private data class Segment(val text: String, val bold: Boolean)

private fun textNodeOf(text: String, style: String, fontWeight: String?, color: String?): TreeNode {
    val bindings = buildJsonObject {
        put("text", JsonPrimitive(text))
        put("style", JsonPrimitive(style))
        if (fontWeight != null) put("fontWeight", JsonPrimitive(fontWeight))
        if (color != null) put("color", JsonPrimitive(color))
    }
    return TreeNode(component = "Text", bindings = bindings)
}
