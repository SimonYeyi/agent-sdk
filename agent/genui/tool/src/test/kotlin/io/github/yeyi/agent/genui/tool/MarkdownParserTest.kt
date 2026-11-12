package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownParserTest {

    @Test
    fun `非 md 文本返回 null`() {
        assertNull(parseMarkdown("北京今天晴"))
        assertNull(parseMarkdown(""))
        assertNull(parseMarkdown("   "))
    }

    @Test
    fun `无空格的 hash 不判为 md`() {
        // `#标签` 是社交话题,不是标题
        assertNull(parseMarkdown("#标签"))
        assertNull(parseMarkdown("1.先做A 2.再做B"))
    }

    @Test
    fun `单独的行内加粗不判为 md`() {
        // `**强调**` 在普通文本里也常见,不单独触发
        assertNull(parseMarkdown("**强调** 单独出现"))
    }

    @Test
    fun `H1 标题映射为 headlineLarge Bold`() {
        val root = parseMarkdown("# 标题")!!
        assertEquals("Card", root.component)
        val text = root.children.single()
        assertEquals("Text", text.component)
        assertEquals("标题", text.bindings["text"]!!.jsonPrimitive.content)
        assertEquals("headlineLarge", text.bindings["style"]!!.jsonPrimitive.content)
        assertEquals("Bold", text.bindings["fontWeight"]!!.jsonPrimitive.content)
    }

    @Test
    fun `H2 H3 映射为对应 headline 档位`() {
        assertEquals("headlineMedium", parseMarkdown("## 副标题")!!.children.single().bindings["style"]!!.jsonPrimitive.content)
        assertEquals("headlineSmall", parseMarkdown("### 子标题")!!.children.single().bindings["style"]!!.jsonPrimitive.content)
    }

    @Test
    fun `无序列表合并为 Column 每项加项目符号`() {
        val root = parseMarkdown("- a\n- b")!!
        val column = root.children.single()
        assertEquals("Column", column.component)
        assertEquals(2, column.children.size)
        assertEquals("• a", column.children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("• b", column.children[1].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("4", column.bindings["spacing"]!!.jsonPrimitive.content)
    }

    @Test
    fun `有序列表保留原序号`() {
        val root = parseMarkdown("1. 第一\n2. 第二")!!
        val column = root.children.single()
        assertEquals("Column", column.component)
        assertEquals("1. 第一", column.children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("2. 第二", column.children[1].bindings["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `段落映射为 bodyLarge 文本`() {
        // 单段不触发 md 判定,用「标题 + 段落」混合结构测段落节点
        val root = parseMarkdown("# 标题\n\n这是一段话")!!
        val para = root.children[1]
        assertEquals("Text", para.component)
        assertEquals("这是一段话", para.bindings["text"]!!.jsonPrimitive.content)
        assertEquals("bodyLarge", para.bindings["style"]!!.jsonPrimitive.content)
    }

    @Test
    fun `引用块映射为 bodyMedium onSurfaceVariant 保留前缀`() {
        val root = parseMarkdown("> 引用文本")!!
        val text = root.children.single()
        assertEquals("Text", text.component)
        assertEquals("> 引用文本", text.bindings["text"]!!.jsonPrimitive.content)
        assertEquals("bodyMedium", text.bindings["style"]!!.jsonPrimitive.content)
        assertEquals("onSurfaceVariant", text.bindings["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `分隔线映射为 Divider`() {
        val root = parseMarkdown("---")!!
        assertEquals("Divider", root.children.single().component)
    }

    @Test
    fun `标题含加粗时剥星号整段 Bold`() {
        // 标题整段本来就 Bold,内部 ** 无视觉差异,剥掉走单段
        val root = parseMarkdown("# **重点** 标题")!!
        val text = root.children.single()
        assertEquals("Text", text.component)
        assertEquals("Bold", text.bindings["fontWeight"]!!.jsonPrimitive.content)
        assertEquals("重点 标题", text.bindings["text"]!!.jsonPrimitive.content)
        // 无 Span 子节点
        assertTrue(text.children.isEmpty())
    }

    @Test
    fun `段落加粗拆成 Text 带 Span children`() {
        // 段落（无 fontWeight）含 ** → Text 的 children 装 Span 子节点
        val root = parseMarkdown("# 标题\n\n**重点** 普通")!!
        val para = root.children[1]
        assertEquals("Text", para.component)
        // 加粗段 + 普通段 两个 Span
        assertEquals(2, para.children.size)
        assertEquals("Span", para.children[0].component)
        assertEquals("重点", para.children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("true", para.children[0].bindings["bold"]!!.jsonPrimitive.content)
        assertEquals("Span", para.children[1].component)
        assertEquals(" 普通", para.children[1].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("false", para.children[1].bindings["bold"]!!.jsonPrimitive.content)
        // Text 自身不带 text 与 fontWeight（加粗语义经 span 表达）
        assertFalse("text" in para.bindings)
        assertFalse("fontWeight" in para.bindings)
    }

    @Test
    fun `段落无加粗时直接 Text 无 Span children`() {
        val root = parseMarkdown("# 标题\n\n普通段落")!!
        val para = root.children[1]
        assertEquals("Text", para.component)
        assertEquals("普通段落", para.bindings["text"]!!.jsonPrimitive.content)
        assertTrue(para.children.isEmpty())
    }

    @Test
    fun `混合结构 按顺序排布`() {
        val root = parseMarkdown("# 标题\n\n段落\n\n- a\n- b")!!
        assertEquals("Card", root.component)
        assertEquals(3, root.children.size)
        assertEquals("Text", root.children[0].component)
        assertEquals("headlineLarge", root.children[0].bindings["style"]!!.jsonPrimitive.content)
        assertEquals("Text", root.children[1].component)
        assertEquals("bodyLarge", root.children[1].bindings["style"]!!.jsonPrimitive.content)
        assertEquals("Column", root.children[2].component)
    }

    @Test
    fun `异类列表各自 flush 成独立 Column`() {
        val root = parseMarkdown("- 无序\n1. 有序")!!
        assertEquals("Card", root.component)
        assertEquals(2, root.children.size)
        assertEquals("Column", root.children[0].component)
        assertEquals("Column", root.children[1].component)
        assertEquals("• 无序", root.children[0].children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("1. 有序", root.children[1].children[0].bindings["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Card 显式绑 spacing=8 避免默认 12dp 累加`() {
        val root = parseMarkdown("# 标题")!!
        assertEquals("8", root.bindings["spacing"]!!.jsonPrimitive.content)
    }

    @Test
    fun `表格映射为 Column 行 Row 列 Text 等宽`() {
        val root = parseMarkdown("| 城市 | 天气 |\n|---|---|\n| 北京 | 晴 |\n| 上海 | 雨 |")!!
        assertEquals("Card", root.component)
        val table = root.children.single()
        assertEquals("Column", table.component)
        assertEquals("8", table.bindings["spacing"]!!.jsonPrimitive.content)
        // 表头 + 2 数据行
        assertEquals(3, table.children.size)
        // 表头行：Bold + weight=1 + maxLines=1
        val headerRow = table.children[0]
        assertEquals("Row", headerRow.component)
        assertEquals("城市", headerRow.children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("Bold", headerRow.children[0].bindings["fontWeight"]!!.jsonPrimitive.content)
        assertEquals("1.0", headerRow.children[0].bindings["weight"]!!.jsonPrimitive.content)
        assertEquals("1", headerRow.children[0].bindings["maxLines"]!!.jsonPrimitive.content)
        // 数据行：无 Bold,weight=1
        val dataRow = table.children[1]
        assertEquals("Row", dataRow.component)
        assertEquals("北京", dataRow.children[0].bindings["text"]!!.jsonPrimitive.content)
        assertEquals("晴", dataRow.children[1].bindings["text"]!!.jsonPrimitive.content)
        assertFalse("fontWeight" in dataRow.children[0].bindings)
    }

    @Test
    fun `缺分隔行的表格不识别 走段落路径`() {
        // 没有 |---|---| 分隔行,不构成 GFM 表格;但 |...| 行仍判为 md(是表格行),
        // 故 parseMarkdown 返回非 null,两行无空行合并成一段落
        val root = parseMarkdown("| a | b |\n| c | d |")!!
        assertEquals("Card", root.component)
        assertEquals(1, root.children.size)
        assertEquals("Text", root.children[0].component)
        // 两行用 \n join 成一段
        assertEquals("| a | b |\n| c | d |", root.children[0].bindings["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `表格后接段落 表格块与段落各自成节点`() {
        val root = parseMarkdown("| k | v |\n|---|---|\n| 1 | 2 |\n\n这是段落")!!
        assertEquals("Card", root.component)
        // 表格 + 段落 两个子节点
        assertEquals(2, root.children.size)
        assertEquals("Column", root.children[0].component)
        assertEquals("Text", root.children[1].component)
        assertEquals("这是段落", root.children[1].bindings["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `表格单元格数不足时用空串补齐`() {
        val root = parseMarkdown("| a | b | c |\n|---|---|---|\n| x | y |")!!
        val table = root.children.single()
        val dataRow = table.children[1]
        // 第三列缺失,补空串
        assertEquals(3, dataRow.children.size)
        assertEquals("", dataRow.children[2].bindings["text"]!!.jsonPrimitive.content)
    }
}
