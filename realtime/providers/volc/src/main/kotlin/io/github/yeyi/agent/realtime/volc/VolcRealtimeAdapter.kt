package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeAdapter
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.Tool
import io.github.yeyi.agent.realtime.TurnDetection
import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

public class VolcRealtimeAdapter(encoding: AudioFormat.Encoding? = null) : RealtimeAdapter {
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val toolsByName = mutableMapOf<String, Tool>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val eventSeq = AtomicInteger(0)

    override val inputAudioFormat: AudioFormat = AudioFormat(
        sampleRateHz = 16_000,
        encoding = encoding ?: AudioFormat.Encoding.PCM_16BIT,
    )

    override val outputAudioFormat: AudioFormat = AudioFormat(
        sampleRateHz = 24_000,
        encoding = encoding ?: AudioFormat.Encoding.PCM_16BIT,
    )

    override val events: Flow<RealtimeEvent> = eventEmitter.asSharedFlow()

    override fun getAuthHeaders(config: SessionConfig): Map<String, String> {
        val apiKey = config.apiKey.split(":")
        return if (apiKey.size == 1) {
            mapOf("X-Api-Key" to apiKey[0])
        } else {
            mapOf(
                "X-Api-App-ID" to apiKey[0],
                "X-Api-Access-Key" to apiKey[1],
            )
        }
    }

    override fun registerTools(tools: List<Tool>) {
        tools.forEach { toolsByName[it.name] = it }
    }

    override fun createSessionFrame(config: SessionConfig): ProtocolFrame {
        return sessionCreateFrame(
            volcSessionConfig(config),
            volcSessionExtensionConfig(config),
        )
    }

    override fun sendAudioFrame(pcm: ByteArray): ProtocolFrame {
        return inputAudioBufferAppendFrame(Base64.getEncoder().encodeToString(pcm))
    }

    override fun commitAudioFrame(): ProtocolFrame {
        return inputAudioBufferCommitFrame()
    }

    override fun commitSpeechTextFrame(text: String): List<ProtocolFrame> {
        return listOf(
            speechTextBufferAppendFrame(text),
            speechTextBufferCommitFrame(),
        )
    }

    override fun cancelResponseFrame(): ProtocolFrame {
        return responseCancelFrame()
    }

    override suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame> {
        val evt = json.decodeFromString(VolcEvent.serializer(), frame.payload.toString())
        return handleIncomingEvent(evt)
    }

    // === 帧构造方法 ===

    private fun sessionCreateFrame(
        session: VolcSessionConfig,
        extension: VolcSessionExtensionConfig?,
    ): ProtocolFrame {
        return buildFrame("session.create") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            if (extension != null) {
                put(
                    "extension",
                    json.encodeToJsonElement(VolcSessionExtensionConfig.serializer(), extension)
                )
            }
        }
    }

    private fun sessionUpdateFrame(
        session: VolcSessionConfig,
        extension: VolcSessionExtensionConfig?,
    ): ProtocolFrame {
        return buildFrame("session.update") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            if (extension != null) {
                put(
                    "extension",
                    json.encodeToJsonElement(VolcSessionExtensionConfig.serializer(), extension)
                )
            }
        }
    }

    private fun sessionCloseFrame(): ProtocolFrame {
        return buildFrame("session.close") { }
    }

    private fun inputAudioBufferAppendFrame(audio: String): ProtocolFrame {
        return buildFrame("input_audio_buffer.append") {
            put("audio", audio)
        }
    }

    private fun inputAudioBufferCommitFrame(): ProtocolFrame {
        return buildFrame("input_audio_buffer.commit") { }
    }

    private fun responseCancelFrame(): ProtocolFrame {
        return buildFrame("response.cancel") { }
    }

    private fun speechTextBufferAppendFrame(text: String): ProtocolFrame {
        return buildFrame("speech_text_buffer.append") {
            put("text", text)
        }
    }

    private fun speechTextBufferCommitFrame(
        prompt: String? = null,
        text: String? = null
    ): ProtocolFrame {
        return buildFrame("speech_text_buffer.commit") {
            put("tts_prompt", prompt)
            put("text", text)
        }
    }

    private fun speechTextBufferReplacementAppendFrame(text: String): ProtocolFrame {
        return buildFrame("speech_text_buffer.replacement.append") {
            put("text", text)
        }
    }

    private fun speechTextBufferReplacementCommitFrame(text: String): ProtocolFrame {
        return buildFrame("speech_text_buffer.replacement.commit") {
            put("text", text)
        }
    }

    private fun conversationItemCreateFrame(items: List<VolcConversationItem>): ProtocolFrame {
        return buildFrame("conversation.item.create") {
            put(
                "items",
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        VolcConversationItem.serializer()
                    ), items
                ),
            )
        }
    }

    private fun conversationItemUpdateFrame(items: List<VolcConversationItem>): ProtocolFrame {
        return buildFrame("conversation.item.update") {
            put(
                "items",
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        VolcConversationItem.serializer()
                    ), items
                ),
            )
        }
    }

    private fun conversationItemRetrieveFrame(items: List<VolcConversationItem>): ProtocolFrame {
        return buildFrame("conversation.item.retrieve") {
            put(
                "items",
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        VolcConversationItem.serializer()
                    ), items
                ),
            )
        }
    }

    private fun conversationItemDeleteFrame(items: List<VolcConversationItem>): ProtocolFrame {
        return buildFrame("conversation.item.delete") {
            put(
                "items",
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        VolcConversationItem.serializer()
                    ), items
                ),
            )
        }
    }

    private fun buildFrame(type: String, body: JsonObjectBuilder.() -> Unit): ProtocolFrame {
        val payload = buildJsonObject {
            put("type", type)
            put("event_id", "event_${eventSeq.incrementAndGet()}")
            body()
        }
        return ProtocolFrame(payload)
    }

    // === 事件处理 ===

    private suspend fun handleIncomingEvent(evt: VolcEvent): List<ProtocolFrame> {
        when (evt.type) {
            "response.function_call_arguments.done" -> {
                return handleFunctionCall(evt)
            }
        }
        toRealtimeEvents(evt).forEach { eventEmitter.emit(it) }
        return emptyList()
    }

    private fun toRealtimeEvents(evt: VolcEvent): List<RealtimeEvent> = when (evt.type) {
        "session.created" ->
            evt.session?.id?.let { listOf(RealtimeEvent.Connected(it)) } ?: emptyList()

        "session.updated" -> emptyList()

        "session.closed" ->
            listOf(RealtimeEvent.Disconnected("session closed"))

        "input_audio_buffer.committed" -> emptyList()

        "conversation.item.input_audio_transcription.started" ->
            evt.itemId?.let { listOf(RealtimeEvent.UserTranscriptStarted(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.delta" ->
            evt.delta?.let { listOf(RealtimeEvent.UserTranscriptDelta(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.completed" ->
            evt.text?.let { listOf(RealtimeEvent.UserTranscriptCompleted(it)) } ?: emptyList()

        "conversation.item.input_audio_transcription.failed" ->
            listOf(
                RealtimeEvent.Error(
                    code = "transcription_failed",
                    message = evt.error?.message ?: "",
                    isFatal = false,
                )
            )

        "response.output_text.delta" ->
            evt.delta?.let { listOf(RealtimeEvent.AssistantTextDelta(it)) } ?: emptyList()

        "response.output_text.done" -> listOf(RealtimeEvent.AssistantTextDone(evt.text ?: ""))

        "response.output_audio.started" ->
            listOf(RealtimeEvent.AssistantAudioStarted)

        "response.output_audio.delta" -> {
            val pcm = evt.delta?.let { Base64.getDecoder().decode(it) } ?: return emptyList()
            listOf(RealtimeEvent.AssistantAudioDelta(pcm))
        }

        "response.output_audio.done" ->
            listOf(RealtimeEvent.AssistantAudioDone)

        "response.canceled" ->
            listOf(RealtimeEvent.ResponseCanceled)

        "response.done" ->
            listOf(RealtimeEvent.ResponseDone)

        "response.function_call_arguments.done" -> emptyList()

        "conversation.item.added",
        "conversation.item.retrieved",
        "conversation.item.updated",
        "conversation.item.deleted" -> emptyList()

        "error" -> listOf(
            RealtimeEvent.Error(
                code = evt.error?.code ?: "unknown",
                message = evt.error?.message ?: "unknown",
                isFatal = false,
            )
        )

        else -> emptyList()
    }

    private suspend fun handleFunctionCall(evt: VolcEvent): List<ProtocolFrame> {
        val calls: List<VolcFunctionCall> = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(VolcFunctionCall.serializer()),
            evt.items!!.toString(),
        )
        return calls.map { call: VolcFunctionCall ->
            val callId = call.callId ?: "function_call.call_id is missing"
            val arguments: JsonElement = call.arguments
                ?.takeIf { it.isNotBlank() }
                ?.let { json.parseToJsonElement(it) }
                ?: JsonObject(emptyMap())
            val output = try {
                if (call.name == null) error("function_call.name missing")
                val tool = toolsByName[call.name] ?: error("tool not registered: ${call.name}")
                tool.execute(arguments)
            } catch (t: Throwable) {
                "工具调用失败: $call : ${t.message ?: t.toString()}"
            }
            toolResultFrame(callId, output)
        }
    }

    private fun toolResultFrame(callId: String, output: String): ProtocolFrame {
        return conversationItemCreateFrame(
            listOf(
                VolcConversationItem(
                    type = "message",
                    role = "tool",
                    callId = callId,
                    content = buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", output)
                        })
                    },
                )
            ),
        )
    }

    // === DTO 构造方法 ===

    private fun volcSessionConfig(config: SessionConfig): VolcSessionConfig =
        VolcSessionConfig(
            id = UUID.randomUUID().toString(),
            model = config.model,
            instructions = config.instructions,
            audio = VolcAudioConfig(
                input = VolcAudioSideConfig(format = inputAudioFormat.toVolcFormatConfig(true)),
                output = VolcAudioSideConfig(
                    format = outputAudioFormat.toVolcFormatConfig(false),
                    voice = config.voice,
                ),
            ),
            tools = config.tools.map { tool ->
                buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                }
            },
        )

    private fun volcSessionExtensionConfig(config: SessionConfig): VolcSessionExtensionConfig {
        val asrExtra = buildJsonObject {
            (config.turnDetection as? TurnDetection.ServerVad)?.thresholdMs?.let {
                put("enable_custom_vad", true)
                put("end_smooth_window_ms", it)
            }
        }
        val dialogExtra = buildJsonObject {
            put(
                "audit_response",
                "抱歉，这个问题我无法回答，你可以换个其他话题，我会尽力为你提供帮助。",
            )
            put("enable_loudness_norm", true)
            put("enable_music", false)
            if (config.turnDetection is TurnDetection.Manual) {
                put("input_mod", "push_to_talk")
            }
        }
        return VolcSessionExtensionConfig(
            asr = VolcExtensionSide(extra = asrExtra),
            tts = VolcExtensionSide(extra = buildJsonObject { }),
            dialog = VolcExtensionDialog(
                location = buildJsonObject { },
                extra = dialogExtra,
            ),
        )
    }

    private fun AudioFormat.toVolcFormatConfig(input: Boolean): VolcFormatConfig {
        val type = when (encoding) {
            AudioFormat.Encoding.PCM_16BIT -> "pcm_s16le"
            AudioFormat.Encoding.PCM_OPUS -> if (input) "speech_opus" else "ogg_opus"
            else -> error("Unsupported encoding: $encoding")
        }
        return VolcFormatConfig(type = type, rate = sampleRateHz)
    }
}
