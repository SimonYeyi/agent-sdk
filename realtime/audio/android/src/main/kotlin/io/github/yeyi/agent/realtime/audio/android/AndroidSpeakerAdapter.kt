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

    private var track: AudioTrack? = null

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
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
    }

    override suspend fun play(pcm: ByteArray) {
        val track = mutex.withLock { track } ?: return
        val written = withContext(Dispatchers.IO) { track.write(pcm, 0, pcm.size) }
        if (written == AudioTrack.ERROR_DEAD_OBJECT) return
        if (written < 0) {
            error("AudioTrack.write failed with code $written (ERROR_INVALID_OPERATION=${AudioTrack.ERROR_INVALID_OPERATION}, ERROR_BAD_VALUE=${AudioTrack.ERROR_BAD_VALUE}, ERROR_DEAD_OBJECT=${AudioTrack.ERROR_DEAD_OBJECT})")
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
