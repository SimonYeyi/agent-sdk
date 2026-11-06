package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.genui.adapter.default.DefaultRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.IosCard
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 演示工具卡片模板注册：方式 2（完全自定义）的渲染实现。
 *
 * 模板只占用专属组件名（Location），不与 iOS 基础组件集同名，
 * 未知组件兜底统一走 iOS 组件集的 [io.github.yeyi.agent.genui.adapter.default.IosFallback]。
 *
 * 三种产卡方式的验证结论：
 * - 方式 1 标准 IR：如 get_datetime，产卡工具直接拼装整棵数据树，引擎递归查表渲染；
 * - 方式 2 完全自定义：如 get_location，产卡只出单模板节点，模板内写原生 Compose。
 *   顶层可复用 iOS 基础组件外壳（IosCard）——外壳只消费根节点参数，children 插槽
 *   直接写原生组件，叶子无需手工构造 TreeNode；
 * - 方式 3 半自定义（已否决）：叶子复用基础组件函数（如 IosText）仍需手工构造
 *   TreeNode，在模板 Compose 中构造节点徒增麻烦，不如直接写原生 Compose。
 *
 * 由 [io.github.yeyi.agent.demo.agent.runtime.AgentRuntime.cardAdapter] 一次性装配。
 */
fun DefaultRenderAdapter.registerDemoCardTemplates() {
    register("Location") { node, _, _, children -> LocationCard(node, children) }
}

// ── 方式 2：完全自定义模板（混合写法：顶层复用 IosCard 外壳，叶子全原生 Compose）──

@Composable
private fun LocationCard(node: TreeNode, actions: @Composable () -> Unit) {
    IosCard(node) {
        Text("当前位置", style = MaterialTheme.typography.titleSmall)
        FieldRow("城市", node.bindingText("city"))
        FieldRow("省份", node.bindingText("province"))
        FieldRow("国家", node.bindingText("country"))
        FieldRow("纬度", node.bindingText("latitude"))
        FieldRow("经度", node.bindingText("longitude"))
        // 动作按钮插槽：引擎递归渲染 root.children（Button 子树），
        // 每个按钮独立绑定白名单动作并回流，多按钮天然支持。
        // 模板只决定按钮的布局位置，不感知按钮实现。
        actions()
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 取绑定值文本；缺失时返回占位符，不让渲染因数据缺字段而崩。 */
private fun TreeNode.bindingText(key: String): String {
    val raw: JsonElement = bindings[key] ?: return "-"
    return (raw as? JsonPrimitive)?.content ?: raw.toString()
}
