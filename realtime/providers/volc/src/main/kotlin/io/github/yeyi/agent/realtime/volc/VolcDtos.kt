package io.github.yeyi.agent.realtime.volc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class VolcEvent(
    val type: String,
    @SerialName("event_id") val eventId: String? = null,
    val session: VolcSession? = null,
    @SerialName("response_id") val responseId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val delta: String? = null,
    val transcript: String? = null,
    val status: String? = null,
    val error: VolcError? = null,
)

@Serializable
internal data class VolcSession(val id: String? = null)

@Serializable
internal data class VolcError(val code: String? = null, val message: String? = null)