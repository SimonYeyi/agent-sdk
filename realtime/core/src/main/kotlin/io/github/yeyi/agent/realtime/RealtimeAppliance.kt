package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class RealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val delegation: RealtimeDelegation,
) {
    private var scope: CoroutineScope? = null
    private val gate = AssistantAudioGate(
        delegationMarker = DELEGATION_MARKER,
        speaker = speaker,
        onDelegate = { asrText -> scope?.launch { runDelegation(asrText) } },
    )

    suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            session.connect(
                sessionConfig.copy(
                    instructions = sessionConfig.instructions + "\n\n" + DELEGATION_PROTOCOL,
                )
            )
            mic.start()
            speaker.start()
            scope?.launch {
                session.events.collect { event -> handleEvent(event) }
            }
            scope?.launch {
                mic.capture().collect { pcm -> session.sendAudio(pcm) }
            }
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    suspend fun close() {
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        mic.close()
        speaker.close()
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted ->
                gate.onUserTranscriptCompleted(event.text)

            is RealtimeEvent.AssistantTextDelta ->
                gate.onTextDelta(event.text)

            is RealtimeEvent.AssistantAudioDelta ->
                gate.onAudioDelta(event.pcm)

            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseDone -> gate.onTurnEnd()

            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()
        val result = delegation.run(asrText)
        val text = renderResult(result)
        session.injectAndRespond(text)
    }

    private fun renderResult(result: DelegationResult): String = when (result) {
        is DelegationResult.Success -> result.text
        is DelegationResult.Failure -> result.message
    }

    internal companion object {
        const val DELEGATION_MARKER = "<|TASK|>"
        val DELEGATION_PROTOCOL = """
            委派协议:
            1. 闲聊 (问候/聊天/知识问答/一般咨询): 直接自然口语回答.
            2. 需要执行任务 (操作设备/调用服务/多步执行):
               assistant 输出**必须**以 $DELEGATION_MARKER 开头, 紧接对用户的简短确认.

               完整示例 (用户说"帮我调暗客厅灯"):

                   $DELEGATION_MARKER 好的, 正在为您调暗客厅灯, 请稍等

               要求:
               - 简短确认**必须用进行时** (表达"正在处理"), 不能用完成时承诺结果.
               - $DELEGATION_MARKER 是内部路由信号, **绝对不能**在 TTS 中读出来.
        """.trimIndent()
    }
}