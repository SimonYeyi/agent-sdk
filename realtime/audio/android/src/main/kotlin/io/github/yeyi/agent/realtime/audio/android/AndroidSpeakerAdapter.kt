package io.github.yeyi.agent.realtime.audio.android

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidSpeakerAdapter(
    private val sampleRateHz: Int = 24_000,
) : SpeakerAdapter {

    override val outputFormat = AudioFormat(
        sampleRateHz = sampleRateHz,
        channels = 1,
        sampleBits = 16,
        encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
    )

    private val mutex = Mutex()
    @Volatile private var track: AudioTrack? = null

    override suspend fun start() {
        mutex.withLock {
            if (track != null) return
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AndroidAudioFormat.CHANNEL_OUT_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
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
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
    }

    override suspend fun play(pcm: ByteArray) {
        val t = track ?: return
        runBlocking { t.write(pcm, 0, pcm.size) }
    }

    override suspend fun stopPlayback() {
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    override suspend fun close() {
        mutex.withLock {
            track?.release()
            track = null
        }
    }
}
