package io.github.yeyi.agent.realtime.volc

import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import io.github.yeyi.agent.realtime.DelegationHandler
import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

public class VolcRealtimeAppliance(
    private val sessionConfig: SessionConfig,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private val protocolAdapter = VolcRealtimeAdapter()
    private var engine: SpeechEngine? = null
    private var scope: CoroutineScope? = null
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val json = Json { ignoreUnknownKeys = true }

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
            }
            configInitParams(engine, sessionConfig)
            val ret = engine.initEngine()
            check(ret == SpeechEngineDefines.ERR_NO_ERROR) {
                "SpeechEngine.initEngine() failed: $ret"
            }
            engine.setListener(EngineListener(::handleSdkMessage))
            this.engine = engine

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

            scope?.launch {
                protocolAdapter.events.collect { event ->
                    val handled = delegationHandler?.handle(event) ?: event
                    eventEmitter.emit(handled)
                }
            }
            delegationHandler?.start()
        } catch (t: Throwable) {
            runCatching { close() }
            throw t
        }
    }

    override suspend fun close() {
        engine?.destroyEngine()
        engine = null
        scope?.cancel()
        scope = null
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