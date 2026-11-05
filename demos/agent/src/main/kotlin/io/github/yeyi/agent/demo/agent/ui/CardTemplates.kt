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
 * 卡片组件模板注册:把卡片 IR 的组件名映射到 Compose 渲染实现。
 *
 * 组件名与产卡工具一一对应（产卡工具写 component，模板解释 component）：
 * Location / DateTime。未知组件走兜底渲染，保证卡片不空白也不中断。
 */
object CardTemplates {

    /** 单例适配器：模板注册一次即可，渲染实现本身无状态。 */
    val adapter: DefaultRenderAdapter by lazy {
        DefaultRenderAdapter().apply {
            register("Location") { node, _, _ -> LocationCard(node) }
            register("DateTime") { node, _, _ -> DateTimeCard(node) }
            registerFallback { node, _, _ -> FallbackCard(node) }
        }
    }
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
