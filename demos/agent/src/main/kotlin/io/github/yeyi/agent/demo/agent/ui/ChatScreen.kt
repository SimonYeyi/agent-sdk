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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    LaunchedEffect(liveBubble?.text, displayItems.size) {
        if (displayItems.isNotEmpty()) {
            listState.scrollToItem(displayItems.size - 1, Int.MAX_VALUE)
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
            OutlinedButton(onClick = { viewModel.clearMessages() }) {
                Text("New")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        viewModel.sendUserInput(input.trim())
                        input = ""
                    }
                },
                enabled = !isProcessing && input.isNotBlank(),
            ) { Text("Send") }
        }
    }
}