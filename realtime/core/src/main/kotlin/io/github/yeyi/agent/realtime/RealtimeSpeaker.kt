package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 装饰 SpeakerAdapter,持有 PCM 缓存并根据用户说话状态自行闭嘴/恢复。
 *
 * 由调用方在 delegationHandler.handle 之后调 [observed],与 TTS 抑制语义串行。
 * 支持 close → start 重新初始化:内部 channel 懒创建,close 时重置状态。
 */
internal class RealtimeSpeaker(
    private val delegate: SpeakerAdapter,
    private val scopeProvider: () -> CoroutineScope?,
) : SpeakerAdapter {
    private val userQuerying = AtomicBoolean(false)
    private var channel: Channel<ByteArray>? = null

    override suspend fun start(format: AudioFormat) {
        if (channel != null) return
        delegate.start(format)
        userQuerying.set(false)
        channel = Channel<ByteArray>(capacity = Channel.UNLIMITED).also { channel ->
            scopeProvider()?.launch {
                for (pcm in channel) {
                    delegate.play(pcm)
                }
            }
        }
    }

    suspend fun observed(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptStarted -> {
                userQuerying.set(true)
                drain()
                delegate.stopPlayback()
            }

            is RealtimeEvent.AssistantAudioStarted -> {
                userQuerying.set(false)
            }

            is RealtimeEvent.AssistantAudioDelta -> {
                play(event.pcm)
            }

            else -> {}
        }
    }

    override suspend fun play(pcm: ByteArray) {
        if (userQuerying.get()) return
        channel?.send(pcm)
    }

    override suspend fun stopPlayback() {
        delegate.stopPlayback()
    }

    override suspend fun close() {
        channel?.close()
        channel = null
        userQuerying.set(false)
        delegate.close()
    }

    private fun drain() {
        while (channel?.tryReceive()?.isSuccess == true) {
            // 丢弃上一轮 TTS 残留 PCM
        }
    }
}
