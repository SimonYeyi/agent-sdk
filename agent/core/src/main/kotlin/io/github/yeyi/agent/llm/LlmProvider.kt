package io.github.yeyi.agent.llm

import kotlinx.coroutines.flow.Flow

/**
 * Provider implementor contract for LLM backends.
 *
 * - [chat] returns a single [ChatResponse] (non-streaming).
 * - [chatStream] returns a [Flow] of [ChatResponseEvent] that MUST eventually emit exactly one
 *   terminal event:
 *     - [ChatResponseEvent.Done] on successful completion (carries `usage` and `finishReason` when available), or
 *     - [ChatResponseEvent.Error] on parse / protocol failure.
 * - Implementations SHOULD throw [io.github.yeyi.agent.AgentException] subtypes
 *   for transport / protocol errors rather than raw Ktor exceptions.
 * - For multi-tool-call streams, each tool call begins with a [ChatResponseEvent.ToolCallDelta]
 *   whose `name` is non-null (marking the start), followed by one or more continuation
 *   [ChatResponseEvent.ToolCallDelta] events. On continuation chunks `name` carries no meaning
 *   and consumers must ignore it (it may be null or repeat the first delta's value).
 *   Every [ChatResponseEvent.ToolCallDelta] carries a non-null `id` (enforced by type); providers
 *   are responsible for filling it on continuation chunks (the consumer trusts the id is stable).
 *   Upstream protocols guarantee the id on each tool call's first chunk / start block, so a missing
 *   one is a protocol violation and decoders fail fast (non-null assertion) instead of emitting an
 *   anonymous delta.
 */
public interface LlmProvider {
    public val name: String

    public suspend fun chat(request: ChatRequest): ChatResponse
    public fun chatStream(request: ChatRequest): Flow<ChatResponseEvent>
}
