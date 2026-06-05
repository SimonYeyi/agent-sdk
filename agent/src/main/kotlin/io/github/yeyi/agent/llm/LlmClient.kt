package io.github.yeyi.agent.llm

import kotlinx.coroutines.flow.Flow

public interface LlmClient {
    public val providerName: String

    public suspend fun chat(request: ChatRequest): ChatResponse
    public fun chatStream(request: ChatRequest): Flow<StreamEvent>
}
