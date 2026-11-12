package io.github.yeyi.agent.demo.agent.ui

import android.app.Application
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.demo.agent.vm.UiMessage
import io.github.yeyi.agent.demo.agent.vm.SessionUiState
import io.github.yeyi.agent.demo.agent.vm.SessionViewModel
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SessionScreen(
    onBack: () -> Unit
) {
    // ViewModel 生命周期 = 屏幕生命周期：组合内创建，离开组合（返回 chat）时
    // 由 DisposableEffect 触发 onCleared 作废 agent，不再挂靠 Activity 长驻。
    val context = LocalContext.current
    val viewModel = remember {
        SessionViewModel(context.applicationContext as Application)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.onCleared() }
    }

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
            // 返回 chat：组合销毁，DisposableEffect onDispose 触发 onCleared 清理 agent
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
                cardAdapter = viewModel.cardAdapter,
                onCardAction = viewModel::onCardAction,
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
    cardAdapter: UiRenderAdapter,
    onCardAction: (ActionEvent) -> Unit,
        modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val displayItems = remember(uiState.messages, uiState.liveBubble) {
        val live = uiState.liveBubble
        if (live == null) uiState.messages
        else uiState.messages + UiMessage.Assistant(live.text, id = live.id)
    }

    // 工具结果是轮次中间过程，不渲染（卡片例外：卡片按锚点替换工具结果后单独成条）。
    // 工具结果消息仍留在 ViewModel 消息列表里充当卡片锚点——卡片的位置即它替代的那次调用。
    val visibleItems = remember(displayItems) {
        displayItems.filter { it !is UiMessage.ToolExecution }
    }

    // autoScroll: 是否处于"底部状态"。仅在此状态下新内容才追底滚屏;
    // 用户上滑查看旧消息后置 false,滑回底部/发消息/清屏后恢复 true。
    // 解决:页面刚恢复时消息异步加载完成会让 visibleItems.size 从 0 变 N,
    // 无条件追底会把用户已上滑的位置拉回底部。
    var autoScroll by remember { mutableStateOf(true) }

    // 用户手势检测:nestedScroll 的 onPreScroll 在每帧滚动前同步回调,
    // source 由手势链路直接标注 UserInput。这是第一手信号,不经过任何
    // 协程取消/竞争窗口;程序 scrollToItem 直接改 scrollPosition,不经过
    // nestedScroll dispatch,不会误触发。
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

    // 标记是否触发了加载更多（用于区分初始加载和加载更多完成）
    var loadMoreTriggered by remember { mutableStateOf(false) }

    // 下拉加载更多：滚动到顶部时触发
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && uiState.hasMorePages && !uiState.isLoadingMore && displayItems.isNotEmpty()) {
            loadMoreTriggered = true
            onLoadMore()
        }
    }

    // chaser: 内容变化时,仅在 autoScroll(底部状态)下把视图追到底部。
    // offset 用 Int.MAX_VALUE 会 clamp 到最大滚动位,即最后一项底边贴住
    // 视口底、不能再上划——这正是"完全贴底"。
    val currentSessionId = uiState.currentSession?.id
    LaunchedEffect(currentSessionId, uiState.liveBubble?.text, visibleItems.size, uiState.isLoading, uiState.isLoadingMore) {
        if (visibleItems.isNotEmpty()) {
            // 加载更多完成后（isLoadingMore: true → false）不滚动，保持在当前位置
            if (loadMoreTriggered) {
                loadMoreTriggered = false
                return@LaunchedEffect
            }
            // 会话切换:重置追底状态,让新会话从底部开始
            if (uiState.isLoading) autoScroll = true
            if (autoScroll) {
                val lastIndex = if (uiState.isLoading) visibleItems.size else visibleItems.size - 1
                listState.scrollToItem(lastIndex, Int.MAX_VALUE)
            }
        }
    }

    // resumer: 滚动落定且"几何贴底"时恢复底部状态。
    // 贴底 = 最后一项底边(offset+size)不超过视口底(viewportEndOffset),含容差:
    // - 恰好贴底: == viewportEndOffset
    // - 内容不足一屏: 底边在视口底之上(<)——滚不动,天然底部状态
    // - 用户下划离开底部: 最后一项底边超出视口底(>)——判 false,不恢复
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

    Column(modifier = modifier) {
        if (uiState.currentSession != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .nestedScroll(userDragConnection)
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

                items(visibleItems, key = { it.id }) { message ->
                    MessageBubble(
                        message,
                        cardAdapter = cardAdapter,
                        onCardAction = onCardAction
                    )
                }

                if (uiState.isLoading) {
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