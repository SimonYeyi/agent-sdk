package io.github.yeyi.agent.demo.realtime.s2s

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.yeyi.agent.demo.realtime.ui.TaskDashboard
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitS2sScreen(
    apiKey: String,
    boss: BossAgent,
    sessionId: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val viewModel: S2sViewModel = viewModel(
        factory = S2sViewModel.Factory(
            applicationContext = context.applicationContext,
            apiKey = apiKey,
            boss = boss,
            sessionId = sessionId,
        )
    )
    val state by viewModel.state.collectAsState()
    val taskGroups by viewModel.taskGroups.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            // 仅转发退出意图（宿主 finish），资源释放由 VM.onCleared 兜底
            onBack()
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll when messages or pending text changes.
    // 首次挂载（session 恢复）直接跳到底部，无动画；之后实时消息用动画滚动。
    var initScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.messages.size, state.pendingUser, state.pendingAssistant) {
        if (state.messages.isNotEmpty() || state.pendingUser.isNotEmpty() || state.pendingAssistant.isNotEmpty()) {
            val lastIndex = (state.messages.size - 1).coerceAtLeast(0)
            if (!initScrolled) {
                listState.scrollToItem(lastIndex)
                initScrolled = true
            } else {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    // Exit when requested（资源释放由 VM.onCleared 兜底，此处仅交由宿主 finish）
    LaunchedEffect(state.shouldExit) {
        if (state.shouldExit) {
            onBack()
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startBridge()
        } else {
            onBack()
        }
    }

    // Auto-connect on mount
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.startBridge()
        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "任务看板")
                }
                Text(
                    text = "车载语音助手",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.connected) "● 聆听中" else "● 连接中...",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // Messages list
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .background(
                                    color = if (isUser)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 4.dp,
                                        bottomEnd = if (isUser) 4.dp else 16.dp,
                                    )
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                // Pending user text
                if (state.pendingUser.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = 16.dp,
                                            bottomEnd = 4.dp,
                                        )
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = state.pendingUser,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                // Pending assistant text
                if (state.pendingAssistant.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = 4.dp,
                                            bottomEnd = 16.dp,
                                        )
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = state.pendingAssistant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        }
    }
}
