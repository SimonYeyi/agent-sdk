package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

public class RealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val delegation: RealtimeDelegation? = null,
) {
    private var scope: CoroutineScope? = null
    private val delegationHandler: DelegationHandler? = delegation?.let { delegation ->
        DelegationHandler(
            session = session,
            delegation = delegation,
            scopeProvider = { scope },
        )
    }

    public val events: Flow<RealtimeEvent> get() = session.events

    public suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            session.connect(sessionConfig.copy(instructions = instructions))
            microphone.start(session.inputAudioFormat)
            speaker.start(session.outputAudioFormat)
            scope?.launch {
                session.events.collect { event -> handleEvent(event) }
            }
            scope?.launch {
                microphone.capture().collect { pcm -> session.sendAudio(pcm) }
            }
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    public suspend fun close() {
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        microphone.close()
        speaker.close()
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        delegationHandler?.handle(event)
        if (event is RealtimeEvent.AssistantAudioDelta) {
            speaker.play(event.pcm)
        }
    }
}

public interface RealtimeDelegation {
    public val capabilities: List<String>
    public suspend fun run(asrText: String): DelegationResult
}

public sealed interface DelegationResult {
    public data class Success(val text: String) : DelegationResult
    public data class Failure(val message: String) : DelegationResult
}

internal class DelegationHandler(
    private val session: RealtimeSession,
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
) {
    private var pendingAsr: String? = null

    fun appendInstructions(base: String): String {
        val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
        return "$base\n\n$DELEGATION_PROTOCOL\n\n可执行能力:\n$capabilityList"
    }

    fun handle(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted -> pendingAsr = event.text
            is RealtimeEvent.AssistantTextDelta -> {
                if (event.text.startsWith(DELEGATION_MARKER).not()) return
                pendingAsr?.let { scopeProvider()?.launch { runDelegation(it) } }
            }

            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseCanceled,
            is RealtimeEvent.Error,
            is RealtimeEvent.ResponseDone -> pendingAsr = null

            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        val text = when (val result = delegation.run(asrText)) {
            is DelegationResult.Success -> result.text
            is DelegationResult.Failure -> result.message
        }
        session.injectAndRespond(text)
    }

    companion object {
        private const val DELEGATION_MARKER = "|"
        private val DELEGATION_PROTOCOL = """
            委派协议：
            1. 闲聊 (问候/聊天/知识问答/一般咨询)：直接自然口语回答。
            2. 需要执行任务（操作设备/调用服务/多步执行）：
               assistant 输出**必须**以 $DELEGATION_MARKER 开头，紧接对用户的简短确认。

               完整示例（用户说“帮我调暗客厅灯”）：

                   $DELEGATION_MARKER 好的，正在为您调暗客厅灯，请稍等

               要求:
               - 简短确认**必须用进行时** (表达“正在处理”), 不能用完成时承诺结果。
               - 只有用户请求落在下面“可执行能力”列表范围内时才走任务委派;其他请求一律按上面的“闲聊”处理,直接回答即可。
        """.trimIndent()
    }
}
