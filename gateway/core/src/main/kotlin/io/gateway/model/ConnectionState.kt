package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
