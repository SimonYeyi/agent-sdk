package io.github.yeyi.agent.realtime.audio.android

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class AndroidMicrophoneAdapter(
    private val sampleRateHz: Int = 16_000,
) : MicrophoneAdapter {

    override val inputFormat = AudioFormat(
        sampleRateHz = sampleRateHz,
        channels = 1,
        sampleBits = 16,
        encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
    )

    @Volatile private var recording: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun capture(): Flow<ByteArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        recording = record
        record.startRecording()
        val buffer = ByteArray(minBuffer)
        try {
            while (isActive) {
                val read = withContext(Dispatchers.IO) { record.read(buffer, 0, buffer.size) }
                if (read > 0) {
                    trySend(buffer.copyOf(read))
                }
            }
        } finally {
            record.stop()
            record.release()
            recording = null
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override suspend fun start() { /* capture() 启动时即开始录音 */ }

    override suspend fun close() {
        recording?.let {
            it.stop()
            it.release()
        }
        recording = null
    }
}
