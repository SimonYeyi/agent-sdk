package io.github.yeyi.agent.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.team.TaskGroupState

/**
 * Main demo screen with task dashboard drawer and chat interface.
 */
@Composable
fun DemoScreen(
    taskGroups: List<TaskGroupState>,
    messages: List<ChatMessageUi>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    scenarioName: String,
    currentScenario: String,
    onScenarioSwitch: () -> Unit,
    voiceMode: Boolean = false,
    onVoiceToggle: () -> Unit = {},
    s2sContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isDrawerOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(modifier = modifier.fillMaxSize()) {
        if (voiceMode && s2sContent != null) {
            s2sContent()
        } else {
            // Full screen chat interface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top bar with scenario name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = scenarioName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onScenarioSwitch() }
                    )
                    Text(
                        text = "🎙",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clickable { onVoiceToggle() }
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = "→ 滑动查看任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

            // Chat messages
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        state = listState
                    ) {
                        items(messages) { message ->
                            ChatBubble(message = message)
                        }
                    }
                }

                // Input area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入指令...") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            focusManager.clearFocus()
                            onSend()
                        }),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            onSend()
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("发送")
                    }
                }
            }
        }

        // Task Dashboard drawer from left - full height left strip for dragging
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(60.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // Open drawer on swipe right
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (dragAmount > 20) {
                                isDrawerOpen = true
                            }
                        }
                    )
                }
        )

        // Task Dashboard drawer overlay
        if (isDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(8.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                isDrawerOpen = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount < -10) {
                                    isDrawerOpen = false
                                }
                            }
                        )
                    }
            ) {
                TaskDashboard(taskGroups = taskGroups)
            }
        }
    }
}

data class ChatMessageUi(
    val role: String,
    val content: String,
    val isLoading: Boolean = false,
    val toolName: String? = null
)

@Composable
fun ChatBubble(message: ChatMessageUi) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart
    val backgroundColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            color = backgroundColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when {
                        isUser -> "用户"
                        isTool -> "工具"
                        else -> "助手"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isLoading) {
                    Text(
                        text = "思考中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
