package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
