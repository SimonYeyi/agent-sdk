package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public enum class ChatType {
    DIRECT_MESSAGE,
    GROUP,
    CHANNEL,
    THREAD
}
