package io.github.yeyi.agent.demo.team.ui

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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.team.TasksState
import kotlinx.coroutines.launch

/**
 * Main boss screen with task dashboard drawer and chat interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BossScreen(
    taskGroups: List<TasksState>,
    messages: List<ChatMessageUi>,
    pendingInputs: List<PendingInput>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onReset: () -> Unit,
    scenarioName: String,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // 输入框以 TextFieldValue 承载文本 + 光标位置：语音回填只更新文本值，光标不会
    // 自动移动，因此语音回调时重建 TextFieldValue 并把光标移到插入文本末尾；
    // 普通打字由 onValueChange 带回最新光标位置，自动跟随。
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(inputText))
    }
    // 语音插入锚点：按下时记录当时的输入框文本与光标位置，interim 期间以这份
    // 固定基准合成（ASR 文本插入到光标之后），避免整段覆盖输入框已有内容。
    var voicePressText by remember { mutableStateOf("") }
    var voiceAnchor by remember { mutableStateOf(0) }
    // ViewModel 侧文本变化（发送后清空等）同步回输入框，光标随之归位到末尾
    LaunchedEffect(inputText) {
        if (fieldValue.text != inputText) {
            fieldValue = fieldValue.copy(text = inputText, selection = TextRange(inputText.length))
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                TaskDashboard(taskGroups = taskGroups)
            }
        }
    ) {
        Box(modifier = modifier.fillMaxSize()) {
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
                    // 重置会话：重建 session 进入全新会话（清空记忆与任务状态）
                    IconButton(onClick = onReset) {
                        Icon(Icons.Default.Refresh, contentDescription = "重置会话")
                    }
                }

            // Chat messages
                val listState = rememberLazyListState()
                // autoScroll: 是否处于"底部状态"。仅在此状态下新内容才追底滚屏;
                // 用户上滑查看旧消息后置 false,滑回底部/发消息/清屏后恢复 true。
                var autoScroll by remember { mutableStateOf(true) }

                // 用户手势检测:nestedScroll 的 onPreScroll 在每帧滚动前同步回调,
                // source 由手势链路直接标注 UserInput。这是第一手信号,不经过任何
                // 协程取消/竞争窗口;而程序 scrollToItem 直接改 scrollPosition,不经过
                // nestedScroll dispatch,不会误触发。避免了"从滚动状态反推原因"的竞争。
                val userDragConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.UserInput) {
                                autoScroll = false
                            }
                            return Offset.Zero
                        }
                    }
                }

                // chaser: 内容变化时把视图追到底部。
                // - 新增用户消息必须无条件拉到底：用户消息经 pendingQueue 延迟确认，
                //   落地前可能处于 autoScroll=false（用户上滑查看历史），但新消息
                //   必须可见，不能落在视口外。
                // - 其余内容变化（流式草稿、工具气泡等）仅在 autoScroll（底部状态）
                //   下追底；offset 用 Int.MAX_VALUE 会 clamp 到最大滚动位，即最后
                //   一项底边贴住视口底、不能再上划——这正是"完全贴底"。滚动时长与
                //   消息尺寸无关，撑满竖屏的流式草稿增量也能被完整追上。
                //   被 delta 重启 cancel 是常态。
                LaunchedEffect(messages) {
                    if (messages.isEmpty()) return@LaunchedEffect
                    val lastIsUser = messages.lastOrNull()?.role == "user"
                    if (autoScroll || lastIsUser) {
                        listState.scrollToItem(messages.size - 1, Int.MAX_VALUE)
                    }
                }

                // resumer: 滚动落定且"几何贴底"时恢复底部状态。
                // 贴底 = 最后一项底边(offset+size)不超过视口底(viewportEndOffset),含容差:
                // - 恰好贴底: == viewportEndOffset
                // - 内容不足一屏: 底边在视口底之上(<)——滚不动,天然底部状态
                // - 用户下划离开底部: 最后一项底边超出视口底(>)——判 false,不恢复
                // 空列表同理判 true(初始即底部)。不依赖 canScrollForward:实测它在
                // 多种场景下与滚动位置脱节(恒 false),无法区分"贴底"与"离开底部"。
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        val atBottom = info.totalItemsCount == 0 ||
                            (last != null && last.index == info.totalItemsCount - 1 &&
                                last.offset + last.size <= info.viewportEndOffset + 2)
                        listState.isScrollInProgress to atBottom
                    }
                    .collect { (scrolling, atBottom) ->
                        if (!scrolling && atBottom) {
                            autoScroll = true
                        }
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
                            .padding(12.dp)
                            .nestedScroll(userDragConnection),
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
                        value = fieldValue,
                        onValueChange = { newValue ->
                            fieldValue = newValue
                            onInputChange(newValue.text)
                        },
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
                    PressToTalkButton(
                        onInputChange = { text ->
                            // 语音回填（interim/final）：ASR 文本插入到按下时的光标锚点之后，
                            // 保留输入框已有内容，光标跟随到插入文本末尾
                            val anchor = voiceAnchor.coerceIn(0, voicePressText.length)
                            val newText = voicePressText.take(anchor) + text + voicePressText.drop(anchor)
                            fieldValue = TextFieldValue(newText, selection = TextRange(anchor + text.length))
                            onInputChange(newText)
                        },
                        onSend = {
                            // 语音定稿发送与点击发送按钮行为一致：先收起键盘/移除焦点
                            focusManager.clearFocus()
                            onSend()
                        },
                        onVoicePress = {
                            voicePressText = fieldValue.text
                            voiceAnchor = fieldValue.selection.end
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
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
    val isError = message.role == "error"
    val alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart
    val backgroundColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.tertiaryContainer
        isSystem -> MaterialTheme.colorScheme.surfaceVariant
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            // 助手气泡（含流式草稿）撑满横屏宽度，避免流式文本增长时宽度跳变；
            // 用户/工具气泡保持按内容伸缩。
            modifier = if (message.role == "assistant") Modifier.fillMaxWidth() else Modifier,
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
