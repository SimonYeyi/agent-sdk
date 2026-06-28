package io.github.yeyi.agent.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.app.vm.UiMessage
import io.github.yeyi.agent.app.vm.SessionUiState
import io.github.yeyi.agent.app.vm.SessionViewModel
import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SessionScreen(
    viewModel: SessionViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            // Handle error display
        }
    }

    LaunchedEffect(uiState.currentSession) {
        if (uiState.currentSession != null && drawerState.isOpen) {
            drawerState.close()
        }
    }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            onBack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    sessions = uiState.sessions,
                    currentSessionId = uiState.currentSession?.id,
                    onSelect = {
                        viewModel.selectSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { viewModel.deleteSession(it) },
                    onCreate = { viewModel.createSession(it) },
                    onCreateAndSelect = {
                        viewModel.createSessionAndSelect(it)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.width(300.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(uiState.currentSession?.name?.ifEmpty { "Chat" } ?: "Sessions") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { padding ->
            ChatArea(
                uiState = uiState,
                onInputChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage() },
                onLoadMore = { viewModel.loadMoreMessages() },
                                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun SessionDrawerContent(
    sessions: List<io.github.yeyi.agent.session.Session>,
    currentSessionId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
    onCreateAndSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.padding(8.dp)) {
        Button(
            onClick = {
                scope.launch {
                    onCreateAndSelect("")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("New Session")
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(sessions) { session ->
                SessionItem(
                    name = session.name.ifEmpty { "Unnamed" },
                    isSelected = session.id == currentSessionId,
                    onClick = { onSelect(session.id) },
                    onDelete = { onDelete(session.id) }
                )
            }
        }
    }
}

@Composable
private fun SessionItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ChatArea(
    uiState: SessionUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadMore: () -> Unit,
        modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val displayItems = remember(uiState.messages, uiState.liveBubble) {
        val live = uiState.liveBubble
        if (live == null) uiState.messages
        else uiState.messages + UiMessage.Assistant(live.text, id = live.id)
    }

    // 检测是否在底部附近
    val isAtBottom = remember(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, displayItems.size) {
        listState.firstVisibleItemIndex >= displayItems.size - 2
    }

    // 记录触发加载时用户是否在底部
    var wasAtBottomBeforeLoad by remember { mutableStateOf(true) }

    // 下拉加载更多：滚动到顶部时触发
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && uiState.hasMorePages && !uiState.isLoadingMore && displayItems.isNotEmpty()) {
            wasAtBottomBeforeLoad = isAtBottom
            onLoadMore()
        }
    }

    // 新消息或流式更新时，滚动到底部显示最新内容（仅当用户原本在底部时才滚动）
    LaunchedEffect(uiState.liveBubble?.text, displayItems.size, uiState.isToolExecutionPending, uiState.isLoadingMore) {
        if (displayItems.isNotEmpty() && wasAtBottomBeforeLoad && !uiState.isLoadingMore) {
            val lastIndex = if (uiState.isToolExecutionPending) displayItems.size else displayItems.size - 1
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    Column(modifier = modifier) {
        if (uiState.currentSession != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // 已到顶部，无更多消息（显示在列表最上方）
                if (!uiState.hasMorePages) {
                    item(key = "no-more-messages") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "没有更多消息了",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(displayItems, key = { it.id }) { message ->
                    MessageBubble(message)
                }

                if (uiState.isToolExecutionPending) {
                    item(key = "loading-indicator") {
                        LoadingBubble()
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("输入您的问题开始对话")
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入您的问题...") },
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = !uiState.isLoading && uiState.inputText.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}