package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.demo.agent.vm.UiMessage
import io.github.yeyi.agent.llm.text

@Composable
fun MessageBubble(message: UiMessage, modifier: Modifier = Modifier) {
    when (message) {
        is UiMessage.User -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(message.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        is UiMessage.Assistant -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(message.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is UiMessage.ToolInProgress -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "🔧 ${message.toolName}...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is UiMessage.ToolExecution -> {
            val color = if (message.result.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
            val onColor = if (message.result.isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(color, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "${if (message.result.isError) "❌" else "✅"} ${message.toolName}: ${message.result.parts.text}",
                    color = onColor,
                )
            }
        }
        is UiMessage.Error -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("⚠️ ${message.cause}", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
