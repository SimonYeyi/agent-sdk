package io.gateway.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class MessageId(val value: String) {
    override fun toString(): String = value
}
