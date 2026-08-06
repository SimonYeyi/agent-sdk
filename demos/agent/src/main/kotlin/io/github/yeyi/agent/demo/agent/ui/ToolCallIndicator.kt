package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 显示一次工具调用的 chip,如 "🔧 get_current_time"。
 */
@Composable
fun ToolCallIndicator(toolName: String) {
    AssistChip(
        onClick = {},
        label = { Text("🔧 $toolName") },
        modifier = Modifier.padding(2.dp),
        colors = AssistChipDefaults.assistChipColors(),
    )
}
