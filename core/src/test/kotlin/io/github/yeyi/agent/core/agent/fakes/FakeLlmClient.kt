package io.github.yeyi.agent.core.agent.fakes

import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLlmClient(
    private val nonStreamResponses: List<ChatResponse> = emptyList(),
    private val streamScripts: List<List<StreamEvent>> = emptyList()
) : LlmClient {
    override val providerName: String = "fake"
    val recordedRequests: MutableList<ChatRequest> = mutableListOf()
    private var nonStreamIndex = 0
    private var streamIndex = 0

    override suspend fun chat(request: ChatRequest): ChatResponse {
        recordedRequests += request
        check(nonStreamIndex < nonStreamResponses.size) {
            "FakeLlmClient: chat() called ${nonStreamIndex + 1} times, but only ${nonStreamResponses.size} responses scripted"
        }
        return nonStreamResponses[nonStreamIndex++]
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> {
        recordedRequests += request
        check(streamIndex < streamScripts.size) {
            "FakeLlmClient: chatStream() called ${streamIndex + 1} times, but only ${streamScripts.size} scripts available"
        }
        val script = streamScripts[streamIndex++]
        return flow { for (e in script) emit(e) }
    }
}
