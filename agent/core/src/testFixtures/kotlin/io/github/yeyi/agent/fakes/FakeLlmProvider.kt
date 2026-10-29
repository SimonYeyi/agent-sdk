package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeLlmProvider(
    private val nonStreamResponses: List<ChatResponse> = emptyList(),
    private val streamScripts: List<List<ChatResponseEvent>> = emptyList()
) : LlmProvider {
    override val name: String = "fake"

    /**
     * 线程安全: 同一个 provider 会被多个并发 worker (如 DAG 的并行分支) 共享,
     * 普通 ArrayList 的 add 在并发下会抛 ArrayIndexOutOfBoundsException。
     */
    val recordedRequests: MutableList<ChatRequest> = CopyOnWriteArrayList()
    private val nonStreamIndex = AtomicInteger()
    private val streamIndex = AtomicInteger()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        recordedRequests.add(request)
        val index = nonStreamIndex.getAndIncrement()
        check(index < nonStreamResponses.size) {
            "FakeLlmProvider: chat() called ${index + 1} times, but only ${nonStreamResponses.size} responses scripted"
        }
        return nonStreamResponses[index]
    }

    override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> {
        recordedRequests.add(request)
        val scriptIndex = streamIndex.getAndIncrement()
        if (scriptIndex < streamScripts.size) {
            val script = streamScripts[scriptIndex]
            return flow { for (e in script) emit(e) }
        }
        // 未配置流式脚本（或脚本已耗尽）时回退到非流式脚本：按流式协议解码
        // ChatResponse。BossAgent 用户轮以 runStream 驱动 innerAgent，会无条件
        // 走 chatStream；大量不关心流式细节的测试只脚本化 nonStreamResponses，
        // 回退后 chat/chatStream 共享同一响应计数器，保持脚本消费顺序对齐。
        val index = nonStreamIndex.getAndIncrement()
        check(index < nonStreamResponses.size) {
            "FakeLlmProvider: chatStream() called ${index + 1} times, but only ${nonStreamResponses.size} responses scripted and no stream scripts"
        }
        val response = nonStreamResponses[index]
        return flow {
            response.message.content?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatResponseEvent.ContentDelta(it)) }
            response.message.toolCalls.forEach { call ->
                emit(
                    ChatResponseEvent.ToolCallDelta(
                        id = call.id,
                        name = call.name,
                        argumentsDelta = call.arguments.toString(),
                    )
                )
            }
            emit(ChatResponseEvent.Done(usage = response.usage, finishReason = response.finishReason))
        }
    }
}
