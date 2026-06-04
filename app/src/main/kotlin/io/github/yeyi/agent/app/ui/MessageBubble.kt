package io.github.yeyi.agent.app.ui

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
import io.github.yeyi.agent.app.vm.UiMessage

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
                if (message.toolCalls.isNotEmpty()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        message.toolCalls.forEach { call ->
                            ToolCallIndicator(toolName = call.name)
                        }
                    }
                }
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
