package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLlmProvider(
    private val nonStreamResponses: List<ChatResponse> = emptyList(),
    private val streamScripts: List<List<StreamEvent>> = emptyList()
) : LlmProvider {
    override val name: String = "fake"
    val recordedRequests: MutableList<ChatRequest> = mutableListOf()
    private var nonStreamIndex = 0
    private var streamIndex = 0

    override suspend fun chat(request: ChatRequest): ChatResponse {
        recordedRequests += request
        check(nonStreamIndex < nonStreamResponses.size) {
            "FakeLlmProvider: chat() called ${nonStreamIndex + 1} times, but only ${nonStreamResponses.size} responses scripted"
        }
        return nonStreamResponses[nonStreamIndex++]
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> {
        recordedRequests += request
        check(streamIndex < streamScripts.size) {
            "FakeLlmProvider: chatStream() called ${streamIndex + 1} times, but only ${streamScripts.size} scripts available"
        }
        val script = streamScripts[streamIndex++]
        return flow { for (e in script) emit(e) }
    }
}
