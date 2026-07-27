package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

public class RealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val delegation: RealtimeDelegation? = null,
) {
    private var scope: CoroutineScope? = null
    private var userQuerying: Boolean = false
    private var audioChannel: Channel<ByteArray>? = null

    private val delegationHandler: DelegationHandler? = delegation?.let { delegation ->
        DelegationHandler(
            session = session,
            delegation = delegation,
            scopeProvider = { scope },
        )
    }

    public val events: Flow<RealtimeEvent> = MutableSharedFlow(extraBufferCapacity = 64)

    public suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        userQuerying = false
        audioChannel = Channel(capacity = Channel.UNLIMITED)
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            session.connect(sessionConfig.copy(instructions = instructions))
            microphone.start(session.inputAudioFormat)
            speaker.start(session.outputAudioFormat)
            scope?.launch {
                session.events.collect { event ->
                    handleEvent(event)
                    (events as MutableSharedFlow).emit(
                        delegationHandler?.transformEvent(event) ?: event
                    )
                }
            }
            scope?.launch {
                microphone.capture().collect { pcm -> session.sendAudio(pcm) }
            }
            scope?.launch {
                for (pcm in audioChannel!!) {
                    speaker.play(pcm)
                }
            }
            delegationHandler?.start()
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    public suspend fun close() {
        userQuerying = false
        audioChannel?.close()
        audioChannel = null
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        microphone.close()
        speaker.close()
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        delegationHandler?.handle(event)
        when (event) {
            is RealtimeEvent.UserTranscriptStarted -> {
                userQuerying = true
                drainAudioChannel()
                speaker.stopPlayback()
            }

            is RealtimeEvent.AssistantAudioStarted -> {
                userQuerying = false
            }

            is RealtimeEvent.AssistantAudioDelta if !userQuerying -> {
                audioChannel?.send(event.pcm)
            }

            else -> {}
        }
    }

    private fun drainAudioChannel() {
        while (audioChannel?.tryReceive()?.isSuccess == true) {
            // drop pending audio to discard the tail of the previous round's TTS
        }
    }
}

public interface RealtimeDelegation {
    public val capabilities: List<String>
    public val replies: Flow<DelegationReply>
    public suspend fun run(asrText: String)
}

public sealed interface DelegationReply {
    public data class Confirmation(val text: String) : DelegationReply
    public data class Success(val text: String) : DelegationReply
    public data class Failure(val message: String) : DelegationReply
}

internal class DelegationHandler(
    private val session: RealtimeSession,
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
) {
    private var pendingAsr: String? = null

    fun appendInstructions(base: String): String {
        val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
        return "$base\n\n$DELEGATION_PROTOCOL\n$capabilityList"
    }

    fun start() {
        scopeProvider()?.launch {
            delegation.replies.collect { update ->
                val text = when (update) {
                    is DelegationReply.Confirmation -> update.text
                    is DelegationReply.Success -> update.text
                    is DelegationReply.Failure -> update.message
                }
                session.injectAndRespond(text)
            }
        }
    }

    fun handle(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted -> pendingAsr = event.text
            is RealtimeEvent.AssistantTextDelta -> {
                if (event.text.startsWith(DELEGATION_MARKER).not()) return
                pendingAsr?.let { scopeProvider()?.launch { runDelegation(it) } }
            }

            else -> Unit
        }
    }

    /**
     * 清除事件中的委派标记，返回干净的事件.
     */
    fun transformEvent(event: RealtimeEvent): RealtimeEvent {
        return when (event) {
            is RealtimeEvent.AssistantTextDelta if (event.text.startsWith(DELEGATION_MARKER)) -> {
                val cleaned = event.text.removePrefix(DELEGATION_MARKER)
                event.copy(text = cleaned)
            }

            else -> event
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()
        delegation.run(asrText)
    }

    companion object {
        private const val DELEGATION_MARKER = "|"
        private const val AVAILABLE_CAPABILITIES_LABEL = "可用能力"
        private val DELEGATION_PROTOCOL = """
            委派协议：
            1. 闲聊 (问候/聊天/知识问答/一般咨询)：直接自然口语回答。
            2. 命中已注册的 function_call 工具：直接发起函数调用，无需标记委派（跳过第3点）。
            3. 落在下面“${AVAILABLE_CAPABILITIES_LABEL}”列表中（不在能力范围内，一律按闲聊处理）：
               assistant 输出**必须**以 $DELEGATION_MARKER 开头标记委派，紧接对用户的简短确认。

               完整示例（用户说“帮我调暗客厅灯”）：

                   ${DELEGATION_MARKER}好的，正在为您调暗客厅灯，请稍等

               要求:
               - 简短确认**必须用进行时** (表达“正在处理”), 不能用完成时承诺结果。
               
               ${AVAILABLE_CAPABILITIES_LABEL}：
        """.trimIndent()
    }
}
