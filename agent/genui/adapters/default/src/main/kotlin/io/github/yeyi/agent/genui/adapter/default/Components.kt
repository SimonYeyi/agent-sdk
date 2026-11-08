package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column as ComposeColumn
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as ComposeText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * default 适配器配套的 iOS 风格常用组件实现 + 注册入口。
 *
 * 组件名与渲染层绑定（自研注册表 / A2UI 目录）保持同一套语义名：Card/Column/Row/Text/
 * Button/Badge/Divider/Spacer/Image。属性经 [TreeNode.bindings] 传入，全部带默认值，
 * 缺省即降级为最常用形态，工具作者无需感知 Compose 细节。
 *
 * 可见性边界：仅 [Card] 对外公开，供自定义模板顶层复用卡片外壳（children 插槽
 * 直接写原生 Compose，无需构造叶子节点）；其余组件均为本模块内部渲染实现，
 * 由注册表查表驱动，外部不应绕过注册表直接调用。
 */
public fun DefaultRenderAdapter.registerDefaultComponents() {
    register("Card") { node, _, children -> Card(node, children) }
    register("Column") { node, _, children -> Column(node, children) }
    register("Row") { node, _, children -> Row(node, children) }
    register("Text") { node, _, _ -> Text(node) }
    register("Button") { node, scope, _ -> Button(node, scope) }
    register("Badge") { node, _, _ -> Badge(node) }
    register("Divider") { node, _, _ -> Divider(node) }
    register("Spacer") { node, _, _ -> Spacer(node) }
    register("Image") { node, _, _ -> Image(node) }
    // 未知组件兜底：渲染为小号灰色占位文本，保证渲染不中断
    registerFallback { node, _, _ -> Fallback(node) }
}

// ─────────────────────────── 绑定数据读取 ───────────────────────────

internal fun Map<String, JsonElement>.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: default

internal fun Map<String, JsonElement>.intValue(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

internal fun Map<String, JsonElement>.floatValue(key: String, default: Float): Float =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: default

internal fun Map<String, JsonElement>.boolValue(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

// ─────────────────────────── iOS 设计语言 ───────────────────────────

/** iOS 系统色板：浅色模式取值，暗色模式由 MaterialTheme 动态语义覆盖。 */
internal object DefaultColors {
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val orange = Color(0xFFFF9F0A)
    val yellow = Color(0xFFFFD60A)
    val teal = Color(0xFF64D2FF)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val indigo = Color(0xFF5E5CE6)
    val gray = Color(0xFF8E8E93)
    val secondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.6f)
    val tertiaryLabel = Color(0xFF3C3C43).copy(alpha = 0.3f)
    val separator = Color(0xFFC6C6C8)
    val fill = Color(0xFFEBEBF0)
    val groupedBackground = Color(0xFFF2F2F7)
    val blueTint = Color(0xFF0A84FF).copy(alpha = 0.12f)

    /** 按语义名解析颜色：hex（#RRGGBB/#AARRGGBB）或 iOS 语义名，未命中返回 null。 */
    fun named(name: String): Color? = when (name.trim().lowercase()) {
        "blue", "primary", "tint" -> blue
        "green" -> green
        "red", "destructive" -> red
        "orange" -> orange
        "yellow" -> yellow
        "teal" -> teal
        "purple" -> purple
        "pink" -> pink
        "indigo" -> indigo
        "gray", "grey", "secondary", "secondarylabel" -> secondaryLabel
        "tertiary", "tertiarylabel" -> tertiaryLabel
        "fill" -> fill
        "separator" -> separator
        "background", "groupedbackground" -> groupedBackground
        "white" -> Color.White
        "black" -> Color.Black
        else -> null
    }
}

/** 绑定中读取颜色：优先 hex，其次语义名，未命中回退默认值。 */
internal fun Map<String, JsonElement>.color(key: String, default: Color): Color {
    val raw = str(key)
    if (raw.isEmpty()) return default
    parseHexColor(raw)?.let { return it }
    return DefaultColors.named(raw) ?: default
}

internal fun parseHexColor(raw: String): Color? {
    val hex = raw.removePrefix("#").trim()
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) {
        Color(0xFF000000 or value)
    } else {
        Color(value)
    }
}

/** iOS 字体排印：正文/标题/说明各档字号与字重，贴近 SF Pro 层级。 */
internal object DefaultType {
    val largeTitle = FontSizeSpec(34f, FontWeight.Bold)
    val title1 = FontSizeSpec(28f, FontWeight.Bold)
    val title2 = FontSizeSpec(22f, FontWeight.SemiBold)
    val title3 = FontSizeSpec(20f, FontWeight.SemiBold)
    val headline = FontSizeSpec(17f, FontWeight.SemiBold)
    val body = FontSizeSpec(17f, FontWeight.Normal)
    val callout = FontSizeSpec(16f, FontWeight.Normal)
    val subheadline = FontSizeSpec(15f, FontWeight.Normal)
    val footnote = FontSizeSpec(13f, FontWeight.Normal)
    val caption = FontSizeSpec(12f, FontWeight.Medium)

    fun named(name: String): FontSizeSpec? = when (name.trim().lowercase()) {
        "largetitle" -> largeTitle
        "title1" -> title1
        "title2" -> title2
        "title3" -> title3
        "headline" -> headline
        "body" -> body
        "callout" -> callout
        "subheadline" -> subheadline
        "footnote" -> footnote
        "caption" -> caption
        else -> null
    }

    fun weight(name: String): FontWeight? = when (name.trim().lowercase()) {
        "regular", "normal" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "semibold", "semi" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        "light" -> FontWeight.Light
        else -> null
    }
}

internal data class FontSizeSpec(val sp: Float, val weight: FontWeight)

internal fun Map<String, JsonElement>.textAlign(key: String, default: TextAlign?): TextAlign? {
    val name = str(key)
    if (name.isEmpty()) return default
    return when (name.trim().lowercase()) {
        "start", "left" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end", "right" -> TextAlign.End
        else -> default
    }
}

internal fun Map<String, JsonElement>.horizontalAlignment(default: Alignment.Horizontal): Alignment.Horizontal {
    val name = str("horizontalAlignment")
    if (name.isEmpty()) return default
    return when (name.trim().lowercase()) {
        "start", "left" -> Alignment.Start
        "center" -> Alignment.CenterHorizontally
        "end", "right" -> Alignment.End
        else -> default
    }
}

internal fun Map<String, JsonElement>.verticalAlignment(default: Alignment.Vertical): Alignment.Vertical {
    val name = str("verticalAlignment")
    if (name.isEmpty()) return default
    return when (name.trim().lowercase()) {
        "top" -> Alignment.Top
        "center" -> Alignment.CenterVertically
        "bottom" -> Alignment.Bottom
        else -> default
    }
}

/** Row 水平排列解析。 */
internal fun Map<String, JsonElement>.horizontalArrangement(
    default: Arrangement.Horizontal,
): Arrangement.Horizontal {
    val name = str("arrangement")
    if (name.isEmpty()) return default
    return when (name.trim().lowercase()) {
        "start" -> Arrangement.Start
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> default
    }
}

/** Column 垂直排列解析。 */
internal fun Map<String, JsonElement>.verticalArrangement(
    default: Arrangement.Vertical,
): Arrangement.Vertical {
    val name = str("arrangement")
    if (name.isEmpty()) return default
    return when (name.trim().lowercase()) {
        "top" -> Arrangement.Top
        "center" -> Arrangement.Center
        "bottom" -> Arrangement.Bottom
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> default
    }
}

// ─────────────────────────── 组件实现 ───────────────────────────

/** 卡片容器：白色圆角矩形 + 柔和阴影，iOS 卡片语义。子内容经 children 插槽排布。 */
@Composable
public fun Card(node: TreeNode, children: @Composable () -> Unit) {
    val spacing = node.bindings.intValue("spacing", 12)
    val padding = node.bindings.intValue("padding", 16)
    val shape = RoundedCornerShape(14.dp)
    ComposeColumn(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, clip = false)
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
    ) {
        children()
    }
}

/** 纵向容器：spacing/arrangement/padding/alignment 均经 bindings 传入，缺省为最常用形态。 */
@Composable
internal fun Column(node: TreeNode, children: @Composable () -> Unit) {
    val spacing = node.bindings.intValue("spacing", 0)
    val padding = node.bindings.intValue("padding", 0)
    // spacing 与 arrangement 互斥：显式间距优先于对齐排列
    val arrangement = if (spacing > 0) {
        Arrangement.spacedBy(spacing.dp)
    } else {
        node.bindings.verticalArrangement(default = Arrangement.Top)
    }
    ComposeColumn(
        modifier = Modifier.padding(padding.dp),
        horizontalAlignment = node.bindings.horizontalAlignment(Alignment.Start),
        verticalArrangement = arrangement,
    ) {
        children()
    }
}

/** 横向容器：spacing/arrangement/padding/alignment 均经 bindings 传入，缺省为最常用形态。 */
@Composable
internal fun Row(node: TreeNode, children: @Composable () -> Unit) {
    val spacing = node.bindings.intValue("spacing", 0)
    val padding = node.bindings.intValue("padding", 0)
    // spacing 与 arrangement 互斥：显式间距优先于对齐排列
    val arrangement = if (spacing > 0) {
        Arrangement.spacedBy(spacing.dp)
    } else {
        node.bindings.horizontalArrangement(default = Arrangement.Start)
    }
    ComposeRow(
        modifier = Modifier.padding(padding.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = node.bindings.verticalAlignment(Alignment.CenterVertically),
    ) {
        children()
    }
}

/** 文本：content 必填，style/size/weight/color/align/maxLines 均可绑定，缺省走 body。 */
@Composable
internal fun Text(node: TreeNode) {
    val content = node.bindings.str("content")
    if (content.isEmpty()) return

    val spec = node.bindings.str("style").let { DefaultType.named(it) } ?: DefaultType.body
    val weight = DefaultType.weight(node.bindings.str("weight")) ?: spec.weight
    val size = node.bindings.floatValue("size", spec.sp)
    val color = node.bindings.color("color", MaterialTheme.colorScheme.onSurface)
    val align = node.bindings.textAlign("align", default = null)
    val maxLines = node.bindings.intValue("maxLines", Int.MAX_VALUE)

    ComposeText(
        text = content,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        textAlign = align,
        maxLines = if (maxLines > 0) maxLines else Int.MAX_VALUE,
        overflow = if (maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** iOS 风格按钮：variant 支持 primary/secondary/bordered/destructive/plain；未声明动作时渲染为禁用态静态文本。 */
@Composable
internal fun Button(node: TreeNode, scope: ActionScope) {
    val action = node.action
    val label = action?.label ?: node.bindings.str("label", "按钮")
    val variant = node.bindings.str("variant", "primary").lowercase()
    val shape = RoundedCornerShape(10.dp)

    if (action == null) {
        // 节点未声明动作：渲染为禁用态静态文本，不绑定点击
        // （动作文案为空的节点由适配器整棵跳过，不会走到这里）
        ComposeText(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = DefaultColors.tertiaryLabel,
        )
        return
    }

    val (background, content, border) = when (variant) {
        "secondary" -> Triple(DefaultColors.fill, DefaultColors.secondaryLabel, Color.Transparent)
        "bordered" -> Triple(Color.Transparent, DefaultColors.blue, DefaultColors.blue.copy(alpha = 0.5f))
        "destructive" -> Triple(DefaultColors.red, Color.White, Color.Transparent)
        "plain" -> Triple(Color.Transparent, DefaultColors.blue, Color.Transparent)
        else -> Triple(DefaultColors.blue, Color.White, Color.Transparent) // primary
    }

    var modifier = Modifier
        .clip(shape)
        .background(background)
        .clickable { scope.emit(action) }
        .padding(horizontal = 14.dp, vertical = 7.dp)
    if (border != Color.Transparent) {
        modifier = modifier.border(1.dp, border, shape)
    }
    ComposeText(
        text = label,
        modifier = modifier,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = content,
    )
}

/** 小圆角标签：content 必填，color/textColor 可绑定。 */
@Composable
internal fun Badge(node: TreeNode) {
    val content = node.bindings.str("content")
    if (content.isEmpty()) return
    val background = node.bindings.color("color", DefaultColors.fill)
    val contentColor = node.bindings.color("textColor", DefaultColors.secondaryLabel)
    ComposeText(
        text = content,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = contentColor,
    )
}

/** 分隔线：默认 iOS separator 色，高度 0.5dp，color 可绑定。 */
@Composable
internal fun Divider(node: TreeNode) {
    val color = node.bindings.color("color", DefaultColors.separator)
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(color)
    )
}

/** 固定占位：height/width 绑定，仅一个方向生效。 */
@Composable
internal fun Spacer(node: TreeNode) {
    val width = node.bindings.floatValue("width", 0f)
    val height = node.bindings.floatValue("height", 0f)
    Box(Modifier.width(width.dp).height(height.dp))
}

/** 图片占位：emoji 于圆角色块中，不引入网络图片依赖。 */
@Composable
internal fun Image(node: TreeNode) {
    val emoji = node.bindings.str("emoji", "🖼")
    val size = node.bindings.floatValue("size", 44f)
    val background = node.bindings.color("background", DefaultColors.fill)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        ComposeText(text = emoji, fontSize = (size * 0.45f).sp)
    }
}

/** 未知组件兜底：小号灰色文本提示，保证渲染不中断。 */
@Composable
internal fun Fallback(node: TreeNode) {
    ComposeText(
        text = "⧗ ${node.component}",
        fontSize = 12.sp,
        color = DefaultColors.tertiaryLabel,
    )
}
