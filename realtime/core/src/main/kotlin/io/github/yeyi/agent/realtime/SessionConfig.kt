package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.serialization.json.JsonElement

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val tools: List<ToolDefinition> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.Silence(),
)

sealed interface TurnDetection {
    data class Silence(val thresholdMs: Int = 600) : TurnDetection
    data class ServerVad(val threshold: Float = 0.5f) : TurnDetection
    data object Manual : TurnDetection
}