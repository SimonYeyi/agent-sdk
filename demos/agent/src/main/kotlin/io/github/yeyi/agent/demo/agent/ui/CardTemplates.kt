package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.genui.adapter.default.DefaultRenderAdapter
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 演示工具卡片组件注册：把卡片 IR 的组件名映射到 Compose 渲染实现。
 *
 * 组件名与产卡工具一一对应（产卡工具写 component，模板解释 component）：
 * Location / DateTime / Text。未知组件走兜底渲染，保证卡片不空白也不中断。
 *
 * 由 [io.github.yeyi.agent.demo.agent.demo.AgentRuntime.cardAdapter] 一次性装配，
 * 与 iOS 风格组件注册共存于同一渲染后端。
 */
fun DefaultRenderAdapter.registerDemoCardComponents() {
    register("Location") { node, _, _, _ -> LocationCard(node) }
    register("DateTime") { node, _, _, _ -> DateTimeCard(node) }
    register("Text") { node, _, _, _ -> TextCard(node) }
    registerFallback { node, _, _, _ -> FallbackCard(node) }
}

@Composable
private fun LocationCard(node: TreeNode) {
    CardTemplate(title = "当前位置") {
        FieldRow("城市", node.bindingText("city"))
        FieldRow("省份", node.bindingText("province"))
        FieldRow("国家", node.bindingText("country"))
        FieldRow("纬度", node.bindingText("latitude"))
        FieldRow("经度", node.bindingText("longitude"))
    }
}

@Composable
private fun DateTimeCard(node: TreeNode) {
    CardTemplate(title = "当前时间") {
        FieldRow("日期", node.bindingText("date"))
        FieldRow("时间", node.bindingText("time"))
    }
}

/** 纯文本卡：卡片正文就是一段文本，没有结构化字段。 */
@Composable
private fun TextCard(node: TreeNode) {
    CardTemplate(title = "搜索结果") {
        FieldRow("关键词", node.bindingText("query"))
        Text(node.bindingText("text"), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FallbackCard(node: TreeNode) {
    CardTemplate(title = node.component) {
        Text("未注册的卡片组件", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardTemplate(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(4.dp))
            content()
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
