package io.github.yeyi.agent.realtime.volc

import android.content.Context
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import io.github.yeyi.agent.realtime.DelegationHandler
import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

public class VolcRealtimeAppliance(
    context: Context,
    private val sessionConfig: SessionConfig,
    private val speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private val applicationContext: Context = context.applicationContext
    private val protocolAdapter = VolcRealtimeAdapter(AudioFormat.Encoding.PCM_OPUS)
    private var engine: SpeechEngine? = null
    private var scope: CoroutineScope? = null
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val json = Json { ignoreUnknownKeys = true }

    private val userQuerying = AtomicBoolean(false)
    private var audioChannel: Channel<ByteArray>? = null

    private val delegationHandler: DelegationHandler? = delegation?.let {
        DelegationHandler(
            delegation = it,
            scopeProvider = { scope },
            onReply = { text ->
                val frames = protocolAdapter.commitSpeechTextFrame(text)
                frames.forEach { frame ->
                    engine?.sendDirective(
                        SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
                        frame.payload.toString(),
                    )
                }
            },
            onReplacementAck = { ack ->
                engine?.sendDirective(
                    SpeechEngineDefines.DIRECTIVE_CANCEL_CURRENT_DIALOG,
                    "",
                )
                protocolAdapter.events
                    .filter { it is RealtimeEvent.ResponseDone || it is RealtimeEvent.ResponseCanceled || it is RealtimeEvent.Error }
                    .first()
                val frames = protocolAdapter.commitSpeechTextFrame(ack)
                frames.forEach { frame ->
                    engine?.sendDirective(
                        SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
                        frame.payload.toString(),
                    )
                }
            },
        )
    }

    override val events: Flow<RealtimeEvent> = eventEmitter.asSharedFlow()

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            protocolAdapter.registerTools(sessionConfig.tools)
            val engine = SpeechEngineGenerator.getInstance().apply {
                createEngine()
                setContext(applicationContext)
            }
            configInitParams(engine, sessionConfig)
            val ret = engine.initEngine()
            check(ret == SpeechEngineDefines.ERR_NO_ERROR) {
                "SpeechEngine.initEngine() failed: $ret"
            }
            engine.setListener(EngineListener(::handleSdkMessage))
            this.engine = engine

            speaker.start(OUTPUT_AUDIO_FORMAT)

            val sessionFrame = protocolAdapter.createSessionFrame(
                sessionConfig.copy(instructions = instructions)
            )
            val startRet = engine.sendDirective(
                SpeechEngineDefines.DIRECTIVE_START_ENGINE,
                sessionFrame.payload.toString(),
            )
            check(startRet == SpeechEngineDefines.ERR_NO_ERROR) {
                "sendDirective(START_ENGINE) failed: $startRet"
            }

            audioChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED).also { channel ->
                scope?.launch {
                    for (pcm in channel) {
                        speaker.play(pcm)
                    }
                }
            }

            scope?.launch {
                protocolAdapter.events.collect { event ->
                    val finalEvent = when {
                        delegationHandler == null -> event
                        else -> delegationHandler.handle(event) ?: return@collect
                    }
                    handleEvent(finalEvent)
                    eventEmitter.emit(finalEvent)
                }
            }
            delegationHandler?.start()
        } catch (t: Throwable) {
            runCatching { close() }
            throw t
        }
    }

    override suspend fun close() {
        userQuerying.set(false)
        engine?.destroyEngine()
        engine = null
        audioChannel?.close()
        audioChannel = null
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        speaker.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptStarted -> {
                userQuerying.set(true)
                drainAudioChannel()
                speaker.stopPlayback()
            }
            is RealtimeEvent.AssistantAudioStarted -> {
                userQuerying.set(false)
            }
            else -> {}
        }
    }

    private fun drainAudioChannel() {
        while (audioChannel?.tryReceive()?.isSuccess == true) {
            // 丢弃上一轮 TTS 残留 PCM
        }
    }

    private fun handleSdkMessage(type: Int, data: ByteArray, len: Int) {
        val scope = scope ?: return
        when (type) {
            SpeechEngineDefines.MESSAGE_TYPE_DIALOG_DOWNLINK_EVENT -> {
                val payload = String(data, 0, len, Charsets.UTF_8)
                val element = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: return
                val jsonObject = element as? JsonObject ?: return
                val frame = ProtocolFrame(jsonObject)
                scope.launch {
                    val replyFrames = protocolAdapter.handleIncomingFrame(frame)
                    replyFrames.forEach { rf ->
                        engine?.sendDirective(
                            SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
                            rf.payload.toString(),
                        )
                    }
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_DECODER_AUDIO_DATA -> {
                if (userQuerying.get()) return
                val pcm = ByteArray(len).also { System.arraycopy(data, 0, it, 0, len) }
                scope.launch { audioChannel?.send(pcm) }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP -> {
                scope.launch { eventEmitter.emit(RealtimeEvent.Disconnected("engine stopped")) }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                val msg = if (len > 0) String(data, 0, len, Charsets.UTF_8) else "engine error"
                scope.launch {
                    eventEmitter.emit(
                        RealtimeEvent.Error(code = "engine_error", message = msg, isFatal = true)
                    )
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START -> {
                // 引擎启动成功(start 已收到 engine 回调);无业务事件
            }
        }
    }

    private fun configInitParams(engine: SpeechEngine, config: SessionConfig) {
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
            SpeechEngineDefines.DIALOG_ENGINE,
        )
        engine.setOptionInt(
            SpeechEngineDefines.PARAMS_KEY_PROTOCOL_TYPE_INT,
            SpeechEngineDefines.PROTOCOL_TYPE_SEED_DUPLEX,
        )
        val (address, uri) = parseEndpoint(config.endpoint)
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING, address)
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING, uri)
        val parts = config.apiKey.split(":")
        if (parts.size == 1) {
            engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_API_KEY_STRING, parts[0])
        } else {
            engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING, parts[0])
            engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING, parts[1])
        }
        engine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING,
            this::class.simpleName,
        )
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, this::class.simpleName)

        // 关闭 SDK 内部播放器,音频由 DECODER_AUDIO_DATA 回调接管自管。
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL, false)
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_DECODER_AUDIO_CALLBACK_BOOL, true)

        // AEC: 录音 + 播放双开时必启用;模型来自 assets/aec/aec.model,首次启动拷贝到 filesDir。
        val aecModelPath = prepareAecModel()
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, true)
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING, aecModelPath)
    }

    private fun prepareAecModel(): String {
        val target = File(applicationContext.filesDir, "aec")
        val modelFile = File(target, "aec.model")
        if (!modelFile.exists()) {
            target.mkdirs()
            applicationContext.assets.open("aec/aec.model").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return modelFile.absolutePath
    }

    private fun parseEndpoint(endpoint: String): Pair<String, String> {
        val parsed = java.net.URI(endpoint)
        val scheme = parsed.scheme ?: "wss"
        val authority = parsed.authority ?: error("endpoint missing host: $endpoint")
        val path = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return "$scheme://$authority" to path
    }

    private class EngineListener(
        private val onMessage: (type: Int, data: ByteArray, len: Int) -> Unit,
    ) : SpeechEngine.SpeechListener {
        override fun onSpeechMessage(type: Int, data: ByteArray, len: Int) {
            onMessage(type, data, len)
        }

        override fun onSpeechLogid(logid: String) {
            // no-op
        }
    }

    private companion object {
        // 火山 dialog 引擎 DECODER_AUDIO_DATA 回调给的是解码后 PCM,SDK 默认采样率 24kHz。
        // 若实际不一致需要按 server response 调整。
        private val OUTPUT_AUDIO_FORMAT = AudioFormat(
            sampleRateHz = 24_000,
            encoding = AudioFormat.Encoding.PCM_16BIT,
        )
    }
}
