package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public sealed class OutgoingContent {

    @Serializable
    public data class Text(
        val text: String,
        val parseMode: ParseMode = ParseMode.PLAIN
    ) : OutgoingContent()

    @Serializable
    public data class Image(
        val url: String,
        val caption: String? = null
    ) : OutgoingContent()

    @Serializable
    public data class Document(
        val url: String,
        val fileName: String
    ) : OutgoingContent()

    @Serializable
    public data class Audio(
        val url: String
    ) : OutgoingContent()
}
