package io.github.yeyi.agent.demo.team.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.team.TasksState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Card displaying a single task group with all its tasks.
 * Tap the card to open a detail dialog with full (untruncated) content.
 */
@Composable
fun TaskGroupCard(
    groupState: TasksState,
    modifier: Modifier = Modifier
) {
    var showDetail by remember { mutableStateOf(false) }
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val createdTime = timeFormat.format(Date(groupState.createdAt))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDetail = true },
        colors = CardDefaults.cardColors(
            containerColor = if (groupState.terminal) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        TaskGroupBody(
            groupState = groupState,
            createdTime = createdTime,
            expanded = false
        )
    }

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("任务详情") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TaskGroupBody(
                        groupState = groupState,
                        createdTime = createdTime,
                        expanded = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetail = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * Shared rendering for both the compact card view and the detail dialog.
 * When [expanded] is false, long text is truncated to one line with ellipsis;
 * when true, text wraps freely with no line limit.
 */
@Composable
private fun TaskGroupBody(
    groupState: TasksState,
    createdTime: String,
    expanded: Boolean
) {
    val maxLines = if (expanded) Int.MAX_VALUE else 1
    val overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis

    Column(modifier = Modifier.padding(12.dp)) {
        // Header: group ID, time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Round: ${groupState.roundId.take(8)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = createdTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // User input
        Text(
            text = "用户: ${groupState.userInput}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = overflow,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Terminal status
        Text(
            text = if (groupState.terminal) "✅ 已完成" else "⏳ 进行中",
            style = MaterialTheme.typography.labelSmall,
            color = if (groupState.terminal) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(top = 2.dp)
        )

        // Task list - show task text once, then events
        Column(modifier = Modifier.padding(top = 8.dp)) {
            for (taskState in groupState.tasks) {
                Text(
                    text = taskState.task,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = maxLines,
                    overflow = overflow,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                for (event in taskState.events) {
                    TaskEventItem(
                        event = event,
                        singleLine = !expanded
                    )
                }
            }
        }
    }
}
