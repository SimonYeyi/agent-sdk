package io.github.yeyi.agent.realtime.volc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class VolcEvent(
    val type: String,
    @SerialName("event_id") val eventId: String? = null,
    val session: VolcSession? = null,
    @SerialName("response_id") val responseId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val delta: String? = null,
    val transcript: String? = null,
    val text: String? = null,
    @SerialName("tts_type") val ttsType: String? = null,
    @SerialName("status_code") val statusCode: String? = null,
    val status: String? = null,
    val items: JsonElement? = null,
    val error: VolcError? = null,
)

@Serializable
internal data class VolcSession(val id: String? = null)

@Serializable
internal data class VolcError(val code: String? = null, val message: String? = null)

@Serializable
internal data class VolcFunctionCall(
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class VolcSessionConfig(
    val id: String? = null,
    val model: String? = null,
    val instructions: String? = null,
    val audio: VolcAudioConfig? = null,
    val tools: List<JsonElement>? = null,
)

@Serializable
internal data class VolcAudioConfig(
    val input: VolcAudioSideConfig? = null,
    val output: VolcAudioSideConfig? = null,
)

@Serializable
internal data class VolcAudioSideConfig(
    val format: VolcFormatConfig? = null,
    val voice: String? = null,
)

@Serializable
internal data class VolcFormatConfig(
    val type: String? = null,
    val rate: Int? = null,
)

@Serializable
internal data class VolcSessionExtensionConfig(
    val asr: VolcExtensionSide? = null,
    val tts: VolcExtensionSide? = null,
    val dialog: VolcExtensionDialog? = null,
)

@Serializable
internal data class VolcExtensionSide(val extra: JsonElement? = null)

@Serializable
internal data class VolcExtensionDialog(
    val location: JsonElement? = null,
    val extra: JsonElement? = null,
)

@Serializable
internal data class VolcConversationItem(
    val type: String,
    val role: String? = null,
    val content: JsonElement? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)