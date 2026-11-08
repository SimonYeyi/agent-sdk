package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.genui.adapter.default.DefaultRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.Card
import io.github.yeyi.agent.genui.adapter.default.ActionScope
import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 演示工具卡片模板注册：方式 2（完全自定义）的渲染实现。
 *
 * 模板只占用专属组件名（Location），不与 default 基础组件集同名，
 * 未知组件兜底统一走 default 组件集的 [io.github.yeyi.agent.genui.adapter.default.Fallback]。
 *
 * 三种产卡方式的验证结论：
 * - 方式 1 标准 IR：如 get_datetime，产卡工具直接拼装整棵数据树，引擎递归查表渲染；
 * - 方式 2 完全自定义：如 get_location，产卡只出单模板节点 + 业务数据。
 *   顶层可复用 default 基础组件外壳（Card）——外壳只消费根节点参数，children 插槽
 *   直接写原生组件，叶子无需手工构造 TreeNode；
 *   模板连按钮与动作一起自持：动作是模板自己的常量，点击经 [ActionScope] 回流，
 *   工具侧不必出现按钮节点，也不必声明动作——卡片定义不再一分为二。
 * - 方式 3 半自定义（已否决）：叶子复用基础组件函数（如 Text）仍需手工构造
 *   TreeNode，在模板 Compose 中构造节点徒增麻烦，不如直接写原生 Compose。
 *
 * 由 [io.github.yeyi.agent.demo.agent.runtime.AgentRuntime.cardAdapter] 一次性装配。
 */
fun DefaultRenderAdapter.registerDemoCardTemplates() {
    // 模板自持动作：id 与文案都在组件契约里，工具只交 root + 数据
    register(component = "Location") { node, scope, _ -> LocationCard(node, scope) }
}

// ── 方式 2：完全自定义模板（混合写法：顶层复用 Card 外壳，叶子全原生 Compose）──

@Composable
private fun LocationCard(node: TreeNode, scope: ActionScope) {
    Card(node) {
        Text("当前位置", style = MaterialTheme.typography.titleSmall)
        FieldRow("城市", node.bindingText("city"))
        FieldRow("省份", node.bindingText("province"))
        FieldRow("国家", node.bindingText("country"))
        FieldRow("纬度", node.bindingText("latitude"))
        FieldRow("经度", node.bindingText("longitude"))
        // 模板自持按钮：动作是模板自己的常量，点击经 scope 回流；
        // 载荷缺省是节点绑定数据（工具给的经纬度等），随动作一起回给模型。
        val action = Action(id = "navigate", label = "开启导航")
        Button(
            onClick = { scope.emit(action) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
        ) {
            Text(action.label)
        }
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
