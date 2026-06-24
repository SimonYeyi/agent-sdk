package io.gateway.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
public value class MessageId(public val value: String) {
    public override fun toString(): String = value
}
