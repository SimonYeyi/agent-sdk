package io.github.yeyi.agent.realtime.audio.android

import android.Manifest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public class AndroidMicrophoneAdapter : MicrophoneAdapter {
    private val mutex = Mutex()
    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private lateinit var audioFormat: AudioFormat

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun start(format: AudioFormat) {
        mutex.withLock {
            if (record != null) return
            audioFormat = format
            val minBuffer = AudioRecord.getMinBufferSize(
                format.sampleRateHz,
                AndroidAudioFormat.CHANNEL_IN_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                format.sampleRateHz,
                AndroidAudioFormat.CHANNEL_IN_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            ).also { it.startRecording() }
        }
    }

    override fun capture(): Flow<ByteArray> = callbackFlow {
        val rec: AudioRecord
        mutex.withLock {
            rec = record ?: error("MicrophoneAdapter not started; call start() first")
            captureJob = currentCoroutineContext()[Job]
        }
        val buffer = ByteArray(
            AudioRecord.getMinBufferSize(
                audioFormat.sampleRateHz,
                AndroidAudioFormat.CHANNEL_IN_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
        )
        try {
            while (isActive) {
                val read = withContext(Dispatchers.IO) { rec.read(buffer, 0, buffer.size) }
                when {
                    read > 0 -> trySend(buffer.copyOf(read))
                    read < 0 -> break
                }
            }
        } finally {
            // record lifecycle owned by start()/close()
        }
        awaitClose { /* nothing to clean; close() handles release */ }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        mutex.withLock {
            captureJob?.cancelAndJoin()
            captureJob = null
            record?.release()
            record = null
        }
    }
}
