package io.github.yeyi.agent.demo.team.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.team.TasksState
import kotlinx.coroutines.launch

/**
 * Main demo screen with task dashboard drawer and chat interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    taskGroups: List<TasksState>,
    messages: List<ChatMessageUi>,
    pendingInputs: List<PendingInput>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    scenarioName: String,
    voiceMode: Boolean = false,
    onVoiceToggle: () -> Unit = {},
    s2sContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = drawerState.isOpen && !voiceMode) {
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(voiceMode) {
        if (voiceMode && drawerState.isOpen) {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !voiceMode,
        drawerContent = {
            // 外层 ModalDrawerSheet 始终渲染,保持 drawerContent width 稳定,
            // 避免 voiceMode 切换时 AnchoredDraggableState 的 anchor 重算导致 drawer 错位。
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                if (!voiceMode) {
                    TaskDashboard(taskGroups = taskGroups)
                }
            }
        }
    ) {
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
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "任务看板")
                    }
                    Text(
                        text = scenarioName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "🎙",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clickable { onVoiceToggle() }
                            .padding(end = 8.dp)
                    )
                }

            // Chat messages
                val listState = rememberLazyListState()
                // 首次挂载（session 恢复）直接跳到底部，无动画；
                // 之后消息实时追加时用动画滚动到最新。
                var initScrolled by remember { mutableStateOf(false) }
                LaunchedEffect(messages.size) {
                    if (messages.isEmpty()) return@LaunchedEffect
                    if (!initScrolled) {
                        listState.scrollToItem(messages.size - 1)
                        initScrolled = true
                    } else {
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

                // Pending queue indicator anchored above the input field.
                // 指令发出后 BossAgent 正在执行报告轮次时会先排队，未收到 Initial
                // 前展示于此；确认（Initial 到达）后移除，队列清空自动关闭。
                val queuedInputs = pendingInputs.filter { it.queued }
                if (queuedInputs.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = "指令排队中",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            queuedInputs.forEach { item ->
                                Text(
                                    text = "• ${item.text}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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

        }
    }
}

data class ChatMessageUi(
    val role: String,
    val content: String,
    val isLoading: Boolean = false,
    val toolName: String? = null
)

/**
 * 发送后待确认的指令：queued=false 为 200ms 超时窗口内的等待确认态（不展示），
 * queued=true 表示已转入排队态，展示在输入框上方的悬浮列表中提示等待执行；
 * 收到对应 Initial 事件（Agent 开始执行）后移除并落为用户气泡。
 */
data class PendingInput(
    val text: String,
    val queued: Boolean
)

@Composable
fun ChatBubble(message: ChatMessageUi) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"
    val alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart
    val backgroundColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.tertiaryContainer
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
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
                        isTool -> "工具：${message.toolName}"
                        isSystem -> "系统"
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
