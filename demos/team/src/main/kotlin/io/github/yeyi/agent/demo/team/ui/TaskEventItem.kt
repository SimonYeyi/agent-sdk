package io.github.yeyi.agent.demo.team.ui

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
import io.github.yeyi.agent.llm.text

/**
 * Displays a single task event with appropriate icon and content.
 * When [singleLine] is true (dashboard card), text is truncated to one line with ellipsis.
 * When false (detail dialog), text wraps freely with no truncation.
 */
@Composable
fun TaskEventItem(
    event: AgentEvent,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    val maxLines = if (singleLine) 1 else Int.MAX_VALUE
    val overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip
    val (icon, color, text) = when (event) {
        is AgentEvent.ToolCallStart -> Triple("⏳", MaterialTheme.colorScheme.primary, "开始: ${event.toolName}")
        is AgentEvent.ToolCallEnd -> {
            val result = event.result.parts.text.takeIf { it.isNotEmpty() } ?: "完成"
            Triple("✓", MaterialTheme.colorScheme.tertiary, "完成: $result")
        }
        is AgentEvent.Final -> {
            val content = (event.result as? AgentResult)?.message?.content ?: "完成"
            Triple("🏁", MaterialTheme.colorScheme.tertiary, content)
        }
        is AgentEvent.Failed -> Triple("✗", MaterialTheme.colorScheme.error, "失败: ${event.cause.message ?: "未知错误"}")
        is AgentEvent.Initial -> Triple("•", MaterialTheme.colorScheme.secondary, "初始化")
        is AgentEvent.ToolCallExplanation -> Triple("💬", MaterialTheme.colorScheme.secondary, event.text ?: "说明")
        is AgentEvent.TextDelta -> Triple("📝", MaterialTheme.colorScheme.secondary, event.text)
        else -> Triple("•", MaterialTheme.colorScheme.onSurface, event.toString())
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
    ) {
        Text(text = icon, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier.weight(1f)
        )
    }
}
