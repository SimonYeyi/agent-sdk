package io.github.yeyi.agent.realtime.audio.android

import android.media.AudioFormat as AndroidAudioFormat
import io.github.yeyi.agent.realtime.audio.AudioFormat

internal fun AudioFormat.Encoding.toAndroidEncoding(): Int = when (this) {
    AudioFormat.Encoding.PCM_16BIT -> AndroidAudioFormat.ENCODING_PCM_16BIT
    AudioFormat.Encoding.PCM_32BIT_FLOAT -> AndroidAudioFormat.ENCODING_PCM_FLOAT
    AudioFormat.Encoding.PCM_OPUS -> error("PCM_OPUS is not supported on Android")
}
