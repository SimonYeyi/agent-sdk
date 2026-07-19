package io.github.yeyi.agent.demo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult

/**
 * Displays a single task event with appropriate icon and content.
 */
@Composable
fun TaskEventItem(
    event: AgentEvent,
    modifier: Modifier = Modifier
) {
    val (icon, color, text) = when (event) {
        is AgentEvent.ToolCallStart -> Triple("⏳", MaterialTheme.colorScheme.primary, "开始: ${event.toolName}")
        is AgentEvent.ToolCallEnd -> {
            val result = event.result.content?.toString()?.take(50) ?: "完成"
            Triple("✓", MaterialTheme.colorScheme.tertiary, "完成: $result")
        }
        is AgentEvent.Final -> {
            val content = (event.result as? AgentResult)?.message?.content ?: "完成"
            Triple("🏁", MaterialTheme.colorScheme.tertiary, content)
        }
        is AgentEvent.Failed -> Triple("✗", MaterialTheme.colorScheme.error, "失败: ${event.cause.message?.take(30) ?: "未知错误"}")
        is AgentEvent.Initial -> Triple("•", MaterialTheme.colorScheme.secondary, "初始化")
        is AgentEvent.ToolCallExplanation -> Triple("💬", MaterialTheme.colorScheme.secondary, event.text ?: "说明")
        is AgentEvent.TextDelta -> Triple("📝", MaterialTheme.colorScheme.secondary, event.text)
        else -> Triple("•", MaterialTheme.colorScheme.onSurface, event.toString().take(30))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
