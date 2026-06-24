package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
enum class ParseMode {
    PLAIN,
    MARKDOWN,
    HTML,
    MARKDOWN_V2
}
