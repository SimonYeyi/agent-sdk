package io.github.yeyi.agent.demo.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yeyi.agent.demo.agent.vm.ChatViewModel
import io.github.yeyi.agent.demo.agent.vm.RunMode
import io.github.yeyi.agent.demo.agent.vm.UiMessage

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSession: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val liveBubble by viewModel.liveBubble.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 派生:live bubble 拼到 messages 末尾,id 沿用 ViewModel 给的 sentinel,
    // Final 提交 Assistant 时用同 id,LazyColumn 视为同 item 原地更新——无视觉跳动
    val displayItems: List<UiMessage> = remember(messages, liveBubble) {
        val live = liveBubble
        if (live == null) messages
        else messages + UiMessage.Assistant(live.text, id = live.id)
    }

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

    // chaser: 内容变化时,仅在 autoScroll(底部状态)下把视图追到底部。
    // offset 用 Int.MAX_VALUE 会 clamp 到最大滚动位,即最后一项底边贴住
    // 视口底、不能再上划——这正是"完全贴底"。滚动时长与消息尺寸无关。
    // 被 delta 重启 cancel 是常态,无需特殊处理;被手势抢占也无妨——OFF
    // 信号由 userDragConnection 独立给出,不依赖这里的 catch。
    LaunchedEffect(displayItems) {
        if (autoScroll && displayItems.isNotEmpty()) {
            listState.scrollToItem(displayItems.size - 1, Int.MAX_VALUE)
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mode:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == RunMode.STREAM,
                    onClick = { viewModel.setMode(RunMode.STREAM) },
                    label = { Text("Stream") },
                    enabled = !isProcessing,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == RunMode.BATCH,
                    onClick = { viewModel.setMode(RunMode.BATCH) },
                    label = { Text("Batch") },
                    enabled = !isProcessing,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onNavigateToSession) {
                Text("Sessions")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = {
                autoScroll = true
                viewModel.clearMessages()
            }) {
                Text("New")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(userDragConnection)
        ) {
            items(displayItems, key = { it.id }) { msg -> MessageBubble(message = msg) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Input") },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        autoScroll = true
                        viewModel.sendUserInput(input.trim())
                        input = ""
                    }
                },
                enabled = !isProcessing && input.isNotBlank(),
            ) { Text("Send") }
        }
    }
}