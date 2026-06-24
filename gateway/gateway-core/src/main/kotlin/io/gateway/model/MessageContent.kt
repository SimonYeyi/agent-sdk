package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public sealed class MessageContent {

    @Serializable
    public data class Text(val text: String) : MessageContent()

    @Serializable
    public data class Image(
        val urls: List<String>,
        val caption: String? = null
    ) : MessageContent()

    @Serializable
    public data class Audio(
        val url: String,
        val durationSeconds: Int? = null,
        val transcription: String? = null
    ) : MessageContent()

    @Serializable
    public data class Video(
        val url: String,
        val durationSeconds: Int? = null,
        val thumbnailUrl: String? = null
    ) : MessageContent()

    @Serializable
    public data class Document(
        val url: String,
        val fileName: String,
        val mimeType: String? = null,
        val sizeBytes: Long? = null
    ) : MessageContent()

    @Serializable
    public data class Location(
        val latitude: Double,
        val longitude: Double,
        val name: String? = null
    ) : MessageContent()

    @Serializable
    public data class Contact(
        val name: String,
        val phone: String? = null,
        val email: String? = null
    ) : MessageContent()

    @Serializable
    public data class Reaction(
        val emoji: String,
        val targetMessageId: String
    ) : MessageContent()

    @Serializable
    public data class Command(
        val command: String,
        val args: List<String> = emptyList()
    ) : MessageContent()

    @Serializable
    public data class SystemEvent(
        val eventType: String,
        val data: Map<String, String> = emptyMap()
    ) : MessageContent()

    @Serializable
    public data class Unknown(val rawContent: String) : MessageContent()
}
