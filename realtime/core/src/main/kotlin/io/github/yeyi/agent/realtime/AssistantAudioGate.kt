package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.SpeakerAdapter

internal class AssistantAudioGate(
    private val delegationMarker: String,
    private val speaker: SpeakerAdapter,
    private val onDelegate: (asrText: String) -> Unit,
) {
    private enum class Mode { BUFFERING, PASSTHROUGH, DROPPING }

    private var mode = Mode.BUFFERING
    private val buffer = mutableListOf<ByteArray>()
    private var pendingAsrText: String? = null

    fun onUserTranscriptCompleted(text: String) {
        pendingAsrText = text
    }

    suspend fun onTextDelta(text: String) {
        if (mode == Mode.DROPPING) return
        if (text.startsWith(delegationMarker)) {
            mode = Mode.DROPPING
            val asr = pendingAsrText ?: error("ASR text missing")
            onDelegate(asr)
        } else {
            mode = Mode.PASSTHROUGH
            buffer.forEach { speaker.play(it) }
            buffer.clear()
        }
    }

    suspend fun onAudioDelta(pcm: ByteArray) {
        when (mode) {
            Mode.BUFFERING -> buffer.add(pcm)
            Mode.PASSTHROUGH -> speaker.play(pcm)
            Mode.DROPPING -> Unit
        }
    }

    fun onTurnEnd() {
        mode = Mode.BUFFERING
        buffer.clear()
        pendingAsrText = null
    }
}
