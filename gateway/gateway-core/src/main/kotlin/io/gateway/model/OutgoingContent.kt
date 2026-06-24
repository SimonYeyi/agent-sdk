package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
sealed class OutgoingContent {

    @Serializable
    data class Text(
        val text: String,
        val parseMode: ParseMode = ParseMode.PLAIN
    ) : OutgoingContent()

    @Serializable
    data class Image(
        val url: String,
        val caption: String? = null
    ) : OutgoingContent()

    @Serializable
    data class Document(
        val url: String,
        val fileName: String
    ) : OutgoingContent()

    @Serializable
    data class Audio(
        val url: String
    ) : OutgoingContent()
}
