package io.github.yeyi.agent.realtime.audio.android

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 处理链：动态范围压缩（DRC）→ 线性增益 → 饱和截断。
 * 用来在保持 AEC 链路（[AudioAttributes.USAGE_VOICE_COMMUNICATION]）的前提下，
 * 绕开 OEM 给 voice-call 流设的低音量上限——单纯线性放大很快撞上限削波，
 * 先压缩动态范围再放大可以把安静部分也拉到接近上限，整体响度（RMS）显著提升。
 *
 * 默认 [gain]=1.0 = 无变化。调高 [gain] 配合内置 DRC 可在多数 OEM 上"听感更响"。
 */
public class AndroidSpeakerAdapter(private val gain: Float = 3.0f) : SpeakerAdapter {
    private val mutex = Mutex()
    private var track: AudioTrack? = null
    private val compressor = SimpleCompressor()

    override suspend fun start(format: AudioFormat) {
        mutex.withLock {
            if (track != null) return
            val androidEncoding = format.encoding.toAndroidEncoding()
            val minBuffer = AudioTrack.getMinBufferSize(
                format.sampleRateHz,
                AndroidAudioFormat.CHANNEL_OUT_MONO,
                androidEncoding,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(androidEncoding)
                        .setSampleRate(format.sampleRateHz)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
    }

    override suspend fun play(pcm: ByteArray) {
        val track = mutex.withLock { track } ?: return
        val data = if (gain != 1.0f) applyGain(pcm) else pcm
        val written = withContext(Dispatchers.IO) { track.write(data, 0, data.size) }
        if (written == AudioTrack.ERROR_DEAD_OBJECT) return
        if (written < 0) {
            error("AudioTrack.write failed with code $written (ERROR_INVALID_OPERATION=${AudioTrack.ERROR_INVALID_OPERATION}, ERROR_BAD_VALUE=${AudioTrack.ERROR_BAD_VALUE}, ERROR_DEAD_OBJECT=${AudioTrack.ERROR_DEAD_OBJECT})")
        }
    }

    private fun applyGain(pcm: ByteArray): ByteArray {
        val nSamples = pcm.size / 2
        val samples = ShortArray(nSamples)
        ByteBuffer.wrap(pcm, 0, nSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)

        val lo = Short.MIN_VALUE.toInt()
        val hi = Short.MAX_VALUE.toInt()
        val norm = 1f / 32768f
        for (i in 0 until nSamples) {
            val compressed = compressor.process(samples[i] * norm)
            samples[i] = (compressed * 32768f * gain).toInt().coerceIn(lo, hi).toShort()
        }

        val out = ByteArray(nSamples * 2)
        ByteBuffer.wrap(out)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)
        return out
    }

    /**
     * 单声道反馈压缩器：包络跟踪 + 软拐点压缩 + 平滑增益应用。
     * 状态跨 [applyGain] 调用保持，保证跨 PCM 块的连续压缩。
     */
    private class SimpleCompressor(
        private val threshold: Float = 0.4f,
        private val ratio: Float = 6f,
        private val attackCoeff: Float = 0.05f,
        private val releaseCoeff: Float = 0.001f,
        private val gainSmoothing: Float = 0.05f,
    ) {
        private var envelope = 0f
        private var gain = 1f

        fun process(sample: Float): Float {
            val abs = if (sample < 0) -sample else sample

            envelope = if (abs > envelope) {
                attackCoeff * abs + (1f - attackCoeff) * envelope
            } else {
                releaseCoeff * abs + (1f - releaseCoeff) * envelope
            }

            val targetGain = if (envelope > threshold) {
                val compressedAmp = threshold + (envelope - threshold) / ratio
                compressedAmp / envelope
            } else {
                1f
            }

            gain = gainSmoothing * targetGain + (1f - gainSmoothing) * gain
            return sample * gain
        }
    }

    override suspend fun stopPlayback() {
        mutex.withLock {
            track?.let {
                it.pause()
                it.flush()
                it.play()
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            track?.release()
            track = null
        }
    }
}
