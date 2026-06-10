package io.github.yeyi.agent.llm

import kotlinx.coroutines.flow.Flow

/**
 * Provider implementor contract for LLM backends.
 *
 * - [chat] returns a single [ChatResponse] (non-streaming).
 * - [chatStream] returns a [Flow] of [StreamEvent] that MUST eventually emit exactly one
 *   terminal event:
 *     - [StreamEvent.Done] on successful completion (carries `usage` and `finishReason` when available), or
 *     - [StreamEvent.Error] on parse / protocol failure.
 * - Implementations SHOULD throw [io.github.yeyi.agent.AgentException] subtypes
 *   for transport / protocol errors rather than raw Ktor exceptions.
 * - For multi-tool-call streams, each tool call MUST begin with a [StreamEvent.ToolCallStart]
 *   (id, name), followed by one or more [StreamEvent.ToolCallDelta] events.
 *   Every [StreamEvent.ToolCallDelta] MUST carry a non-null `id`; providers are responsible
 *   for filling it on continuation chunks (the consumer trusts the id is stable). The `name`
 *   field is non-null only on the first delta for a given id and may be null on continuation chunks.
 */
public interface LlmClient {
    public val providerName: String

    public suspend fun chat(request: ChatRequest): ChatResponse
    public fun chatStream(request: ChatRequest): Flow<StreamEvent>
}
