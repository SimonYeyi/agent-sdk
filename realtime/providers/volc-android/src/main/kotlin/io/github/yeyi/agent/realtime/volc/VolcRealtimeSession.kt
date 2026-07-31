package io.github.yeyi.agent.realtime.volc

import android.content.Context
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

internal class VolcRealtimeSession(
    private val context: Context,
    private val adapter: VolcRealtimeAdapter,
) : RealtimeSession {
    private var engine: SpeechEngine? = null
    private var scope: CoroutineScope? = null
    private val json = Json { ignoreUnknownKeys = true }

    private val specialEvents = MutableSharedFlow<RealtimeEvent>()

    override val inputAudioFormat: AudioFormat get() = adapter.inputAudioFormat

    override val outputAudioFormat: AudioFormat get() = adapter.outputAudioFormat

    override val events: Flow<RealtimeEvent>
        get() = merge(
            specialEvents,
            adapter.events.filter { it !is RealtimeEvent.Disconnected }
        )

    override suspend fun connect(config: SessionConfig) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        engine = SpeechEngineGenerator.getInstance().apply {
            createEngine()
            setContext(context.applicationContext)
        }
        configInitParams(engine!!, config)
        val ret = engine!!.initEngine()
        check(ret == SpeechEngineDefines.ERR_NO_ERROR) {
            "SpeechEngine.initEngine() failed: $ret"
        }
        engine!!.setListener(EngineListener(::handleSdkMessage))

        adapter.registerTools(config.tools)
        val sessionFrame = adapter.createSessionFrame(config)
        val startRet = engine!!.sendDirective(
            SpeechEngineDefines.DIRECTIVE_START_ENGINE,
            sessionFrame.payload.toString(),
        )
        check(startRet == SpeechEngineDefines.ERR_NO_ERROR) {
            "sendDirective(START_ENGINE) failed: $startRet"
        }
    }

    override fun close() {
        engine?.destroyEngine()
        engine = null
        scope?.cancel()
        scope = null
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        val frame = adapter.sendAudioFrame(pcm)
        sendUplinkFrame(frame)
    }

    override suspend fun commitAudio() {
        val frame = adapter.commitAudioFrame()
        sendUplinkFrame(frame)
    }

    override suspend fun cancelResponse() {
        val frame = adapter.cancelResponseFrame()
        sendUplinkFrame(frame)
    }

    override suspend fun injectAndRespond(text: String) {
        adapter.commitSpeechTextFrame(text).forEach { sendUplinkFrame(it) }
    }

    private fun sendUplinkFrame(frame: ProtocolFrame) {
        engine?.sendDirective(
            SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
            frame.payload.toString(),
        )
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
                    val replyFrames = adapter.handleIncomingFrame(frame)
                    replyFrames.forEach { sendUplinkFrame(it) }
                }
            }

            SpeechEngineDefines.MESSAGE_TYPE_DECODER_AUDIO_DATA -> {
                val pcm = ByteArray(len).also { System.arraycopy(data, 0, it, 0, len) }
                scope.launch { specialEvents.emit(RealtimeEvent.AssistantAudioDelta(pcm)) }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP -> {
                scope.launch { specialEvents.emit(RealtimeEvent.Disconnected("engine stopped")) }
            }

            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                val msg = if (len > 0) String(data, 0, len, Charsets.UTF_8) else "engine error"
                scope.launch {
                    specialEvents.emit(
                        RealtimeEvent.Error(code = "engine_error", message = msg, isFatal = true)
                    )
                }
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

        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL, false)
        engine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_DECODER_AUDIO_CALLBACK_BOOL,
            true
        )

        val aecModelPath = prepareAecModel()
        engine.setOptionBoolean(SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL, true)
        engine.setOptionString(SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING, aecModelPath)
    }

    private fun prepareAecModel(): String {
        val target = File(context.filesDir, "aec")
        val modelFile = File(target, "aec.model")
        if (!modelFile.exists()) {
            target.mkdirs()
            context.assets.open("aec/aec.model").use { input ->
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
}
