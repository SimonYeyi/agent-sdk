package io.github.yeyi.agent.demo.agent.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.demo.agent.vm.UiMessage
import io.github.yeyi.agent.llm.text

@Composable
fun MessageBubble(message: UiMessage, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
    when (message) {
        is UiMessage.User -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                    .copyOnLongPress(message.text, copy)
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
                    .copyOnLongPress(message.text, copy)
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
            val text = "${if (message.result.isError) "❌" else "✅"} ${message.toolName}: ${message.result.parts.text}"
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(color, RoundedCornerShape(12.dp))
                    .copyOnLongPress(text, copy)
                    .padding(12.dp)
            ) {
                Text(text, color = onColor)
            }
        }
        is UiMessage.Error -> {
            val text = "⚠️ ${message.cause}"
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .copyOnLongPress(text, copy)
                    .padding(12.dp)
            ) {
                Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.copyOnLongPress(text: String, copy: (String) -> Unit): Modifier =
    combinedClickable(onClick = {}, onLongClick = { copy(text) })
