package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column as ComposeColumn
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as ComposeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 当前渲染位置所在的 [RowScope]：Row 渲染子节点时提供，Column 不提供。
 *
 * 让子组件（如 Button）能用 `Modifier.weight()` 按比例分配 Row 剩余宽度——
 * weight 是 RowScope 专属扩展，组件函数本身不在 RowScope 接收者里，故经此 local 桥接。
 * 取不到（null）说明当前不在 Row 里，weight 绑定不生效。
 */
internal val LocalRowScope = compositionLocalOf<RowScope?> { null }

/**
 * default 适配器配套的 Material3 风格常用组件实现 + 注册入口。
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

// ─────────────────────────── 色板 ───────────────────────────

/**
 * 色板：M3 colorScheme 角色 + 纯色名 + hex。
 *
 * M3 角色取浅色调色板的静态默认值（暗色模式不自动跟随——后续可改成 @Composable 读
 * MaterialTheme.colorScheme）。纯色名是固定色值，覆盖 M3 角色之外的扩展色需求。
 * hex 支持 #RRGGBB / #AARRGGBB。未命中返回 null，由调用方走默认值。
 */
internal object DefaultColors {
    // M3 colorScheme 角色（浅色默认值）
    val primary = Color(0xFF6750A4)
    val secondary = Color(0xFF625B71)
    val tertiary = Color(0xFF7D5260)
    val error = Color(0xFFB3261E)
    val surface = Color(0xFFFEF7FF)
    val onSurface = Color(0xFF1D1B20)
    val onSurfaceVariant = Color(0xFF49454F)
    val outline = Color(0xFF79747E)
    val outlineVariant = Color(0xFFCAC4D0)
    val background = Color(0xFFFEF7FF)
    val onBackground = Color(0xFF1D1B20)
    val surfaceVariant = Color(0xFFE7E0EC)

    // 纯色名（固定色值）
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val orange = Color(0xFFFF9F0A)
    val yellow = Color(0xFFFFD60A)
    val teal = Color(0xFF64D2FF)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val indigo = Color(0xFF5E5CE6)

    /** 按名解析颜色：M3 colorScheme 角色名（camelCase）、纯色名（lowercase）、hex，未命中返回 null。 */
    fun named(name: String): Color? = when (name.trim()) {
        // M3 colorScheme 角色（与属性名同名、大小写敏感）
        "primary" -> primary
        "secondary" -> secondary
        "tertiary" -> tertiary
        "error" -> error
        "surface" -> surface
        "onSurface" -> onSurface
        "onSurfaceVariant" -> onSurfaceVariant
        "outline" -> outline
        "outlineVariant" -> outlineVariant
        "background" -> background
        "onBackground" -> onBackground
        "surfaceVariant" -> surfaceVariant
        // 纯色名
        "blue" -> blue
        "green" -> green
        "red" -> red
        "orange" -> orange
        "yellow" -> yellow
        "teal" -> teal
        "purple" -> purple
        "pink" -> pink
        "indigo" -> indigo
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

/** fontWeight 名 → Compose [FontWeight]，取值与 Compose 枚举同名、大小写敏感。 */
internal fun fontWeight(name: String): FontWeight? = when (name.trim()) {
    "Normal" -> FontWeight.Normal
    "Medium" -> FontWeight.Medium
    "SemiBold" -> FontWeight.SemiBold
    "Bold" -> FontWeight.Bold
    "Light" -> FontWeight.Light
    else -> null
}

internal fun Map<String, JsonElement>.textAlign(key: String, default: TextAlign?): TextAlign? {
    val name = str(key)
    if (name.isEmpty()) return default
    return when (name.trim()) {
        "Start" -> TextAlign.Start
        "Center" -> TextAlign.Center
        "End" -> TextAlign.End
        else -> default
    }
}

internal fun Map<String, JsonElement>.horizontalAlignment(default: Alignment.Horizontal): Alignment.Horizontal {
    val name = str("horizontalAlignment")
    if (name.isEmpty()) return default
    return when (name.trim()) {
        "Start" -> Alignment.Start
        "CenterHorizontally" -> Alignment.CenterHorizontally
        "End" -> Alignment.End
        else -> default
    }
}

internal fun Map<String, JsonElement>.verticalAlignment(default: Alignment.Vertical): Alignment.Vertical {
    val name = str("verticalAlignment")
    if (name.isEmpty()) return default
    return when (name.trim()) {
        "Top" -> Alignment.Top
        "CenterVertically" -> Alignment.CenterVertically
        "Bottom" -> Alignment.Bottom
        else -> default
    }
}

/** Row 水平排列解析，取值与 Compose [Arrangement] 枚举同名、大小写敏感。 */
internal fun Map<String, JsonElement>.horizontalArrangement(
    default: Arrangement.Horizontal,
): Arrangement.Horizontal {
    val name = str("arrangement")
    if (name.isEmpty()) return default
    return when (name.trim()) {
        "Start" -> Arrangement.Start
        "Center" -> Arrangement.Center
        "End" -> Arrangement.End
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        else -> default
    }
}

/** Column 垂直排列解析，取值与 Compose [Arrangement] 枚举同名、大小写敏感。 */
internal fun Map<String, JsonElement>.verticalArrangement(
    default: Arrangement.Vertical,
): Arrangement.Vertical {
    val name = str("arrangement")
    if (name.isEmpty()) return default
    return when (name.trim()) {
        "Top" -> Arrangement.Top
        "Center" -> Arrangement.Center
        "Bottom" -> Arrangement.Bottom
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        else -> default
    }
}

// ─────────────────────────── 组件实现 ───────────────────────────

/** 卡片容器：圆角矩形 + 柔和阴影，Material3 surface 语义。子内容经 children 插槽排布。 */
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

/**
 * 纵向容器：spacing/arrangement/padding/alignment/background 均经 bindings 传入，缺省为最常用形态。
 *
 * 绑定 `background`（颜色名或 hex，取值同 Text.color）时画 8dp 圆角色块作底——
 * 引用块、强调区块等「set apart」的视觉用此表达,不必为每种语义各造组件。
 */
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
    val bg = node.bindings.color("background", Color.Transparent)
    val modifier = if (bg != Color.Transparent) {
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(padding.dp)
    } else {
        Modifier.padding(padding.dp)
    }
    ComposeColumn(
        modifier = modifier,
        horizontalAlignment = node.bindings.horizontalAlignment(Alignment.Start),
        verticalArrangement = arrangement,
    ) {
        children()
    }
}

/**
 * 横向容器：spacing/arrangement/padding/alignment/background 均经 bindings 传入，缺省为最常用形态。
 *
 * 绑定 `background`（颜色名或 hex，取值同 Text.color）时画 8dp 圆角色块作底——
 * 引用块、强调区块等「set apart」的视觉用此表达,不必为每种语义各造组件。
 *
 * Row 渲染子节点时提供 [LocalRowScope]，让子组件（如 Button）能绑 `weight`（浮点）
 * 按比例分配剩余宽度——三个按钮各绑 weight=1 即等宽，避免窄屏下 wrap-content 按钮被挤到文案换行。
 * weight 只在 Row 里生效（Column 不提供 LocalRowScope）。
 */
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
    val bg = node.bindings.color("background", Color.Transparent)
    val modifier = if (bg != Color.Transparent) {
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(padding.dp)
    } else {
        Modifier.padding(padding.dp)
    }
    ComposeRow(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalAlignment = node.bindings.verticalAlignment(Alignment.CenterVertically),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRowScope provides this) {
            children()
        }
    }
}

/**
 * 文本：text 必填（或经 Span children 给出），style/fontWeight/textAlign/color/maxLines/weight 均可绑定，缺省走 bodyLarge。
 *
 * 两种文本形态：
 * - 纯文本：bindings.text 给出整段文字
 * - 行内 span：children 装多个 `Span` 子节点（component="Span", bindings.text + bindings.bold），
 *   各段文字合并成一个 [androidx.compose.ui.text.AnnotatedString]——加粗段套 [SpanStyle](fontWeight=Bold)。
 *   span 形态下文本自然换行，不会因 Row 横向排列窄屏错乱；Text 自身不带 fontWeight（加粗语义经 span 表达）。
 *
 * style 取 M3 typography 对应档位的 [TextStyle] 作基线（拿到 lineHeight / lineHeightStyle，
 * 让 Compose 按字体度量自适应行高，避免裸 fontSize 时退化到平台默认行高——
 * 中文大字号下偏紧、满行换行重叠）；fontWeight/textAlign/color 作为覆写——
 * 显式绑了才覆写，未绑保留 M3 档位的默认值。style 名与 M3 档位同名、大小写敏感
 * （headlineLarge / headlineMedium / ... / labelSmall），未命中走 bodyLarge。
 *
 * 绑定 `weight`（浮点）时按比例分配 Row 剩余宽度——表格里每列 Text 各绑 weight=1 即等宽，
 * 让列在 Row 里对齐；weight 只在 Row 里生效（依赖 [LocalRowScope]，与 [Button] 同一机制）。
 */
@Composable
internal fun Text(node: TreeNode) {
    val typo = MaterialTheme.typography
    val styles = mapOf(
        "headlineLarge" to typo.headlineLarge,
        "headlineMedium" to typo.headlineMedium,
        "headlineSmall" to typo.headlineSmall,
        "titleLarge" to typo.titleLarge,
        "titleMedium" to typo.titleMedium,
        "titleSmall" to typo.titleSmall,
        "bodyLarge" to typo.bodyLarge,
        "bodyMedium" to typo.bodyMedium,
        "bodySmall" to typo.bodySmall,
        "labelMedium" to typo.labelMedium,
        "labelSmall" to typo.labelSmall,
    )
    val base = styles[node.bindings.str("style")] ?: typo.bodyLarge
    val weight = fontWeight(node.bindings.str("fontWeight")) ?: base.fontWeight
    val color = node.bindings.color("color", MaterialTheme.colorScheme.onSurface)
    val align = node.bindings.textAlign("textAlign", default = null)
    val maxLines = node.bindings.intValue("maxLines", Int.MAX_VALUE)
    val rowScope = LocalRowScope.current
    val weightValue = node.bindings.floatValue("weight", 0f)
    val weightModifier = if (weightValue > 0f && rowScope != null) {
        with(rowScope) { Modifier.weight(weightValue) }
    } else {
        Modifier
    }

    val spanChildren = node.children.filter { it.component == "Span" }
    if (spanChildren.isEmpty()) {
        val text = node.bindings.str("text")
        if (text.isEmpty()) return
        ComposeText(
            text = text,
            modifier = weightModifier,
            style = base,
            fontWeight = weight,
            color = color,
            textAlign = align,
            maxLines = if (maxLines > 0) maxLines else Int.MAX_VALUE,
            overflow = if (maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
        return
    }

    val annotated = buildAnnotatedString {
        spanChildren.forEach { span ->
            val segText = span.bindings.str("text")
            if (segText.isEmpty()) return@forEach
            val bold = span.bindings.boolValue("bold", false)
            if (bold) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segText) }
            } else {
                append(segText)
            }
        }
    }
    if (annotated.isEmpty()) return
    ComposeText(
        text = annotated,
        modifier = weightModifier,
        style = base,
        color = color,
        textAlign = align,
        maxLines = if (maxLines > 0) maxLines else Int.MAX_VALUE,
        overflow = if (maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/**
 * 按钮：variant 支持 Primary/Secondary/Bordered/Destructive/Plain；未声明动作时渲染为禁用态静态文本。
 *
 * 默认形态：wrap-content（宽度跟文案走）+ 文案左对齐 + 横向 16dp / 纵向 8dp 内边距。
 * 绑定 `weight`（浮点）时按比例分配 Row 剩余宽度——三个按钮各绑 weight=1 在 Row 里等宽，
 * 避免窄屏下 wrap-content 按钮被挤到文案换行；weight 只在 Row 里生效（依赖 [LocalRowScope]）。
 * 文案强制单行（maxLines=1），空间实在不够时省略号截断而非纵向换行。
 * modifier 顺序：weight → clip → background → border → padding → clickable → 文字——
 * background 画在 clip 裁剪的完整盒子上（含 padding 区域），padding 把文字往里推，
 * 按钮是个完整色块而非文字紧贴背景边缘。
 */
@Composable
internal fun Button(node: TreeNode, scope: ActionScope) {
    val action = node.action
    val text = action?.label ?: node.bindings.str("text", "按钮")
    val variant = node.bindings.str("variant", "Primary")
    val shape = RoundedCornerShape(10.dp)
    val weight = node.bindings.floatValue("weight", 0f)

    if (action == null) {
        // 节点未声明动作：渲染为禁用态静态文本，不绑定点击
        // （动作文案为空的节点由适配器整棵跳过，不会走到这里）
        ComposeText(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = DefaultColors.outline,
        )
        return
    }

    val (containerColor, contentColor, border) = when (variant) {
        "Secondary" -> Triple(DefaultColors.surfaceVariant, DefaultColors.onSurfaceVariant, Color.Transparent)
        "Bordered" -> Triple(Color.Transparent, DefaultColors.primary, DefaultColors.primary.copy(alpha = 0.5f))
        "Destructive" -> Triple(DefaultColors.error, Color.White, Color.Transparent)
        "Plain" -> Triple(Color.Transparent, DefaultColors.primary, Color.Transparent)
        else -> Triple(DefaultColors.primary, Color.White, Color.Transparent) // Primary
    }

    // weight 须在 RowScope 里才生效：从 LocalRowScope 桥接，没拿到说明不在 Row 里（weight 不生效）
    val rowScope = LocalRowScope.current
    val weightModifier = if (weight > 0f && rowScope != null) {
        with(rowScope) { Modifier.weight(weight) }
    } else {
        Modifier
    }

    // background 画在 clip 裁剪的完整盒子上（含 padding 区域），padding 把文字往里推
    var modifier = weightModifier
        .clip(shape)
        .background(containerColor)
    if (border != Color.Transparent) {
        modifier = modifier.border(1.dp, border, shape)
    }
    modifier = modifier
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .clickable { scope.emit(action) }
    ComposeText(
        text = text,
        modifier = modifier,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = contentColor,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 小圆角标签：text 必填，containerColor/contentColor 可绑定。 */
@Composable
internal fun Badge(node: TreeNode) {
    val text = node.bindings.str("text")
    if (text.isEmpty()) return
    val containerColor = node.bindings.color("containerColor", DefaultColors.surfaceVariant)
    val contentColor = node.bindings.color("contentColor", DefaultColors.onSurfaceVariant)
    ComposeText(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = contentColor,
    )
}

/** 分隔线：默认 outlineVariant 色，高度 0.5dp，color 可绑定。 */
@Composable
internal fun Divider(node: TreeNode) {
    val color = node.bindings.color("color", DefaultColors.outlineVariant)
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
    val containerColor = node.bindings.color("containerColor", DefaultColors.surfaceVariant)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(containerColor),
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
        color = DefaultColors.outline,
    )
}
