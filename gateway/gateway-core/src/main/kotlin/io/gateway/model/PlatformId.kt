package io.gateway.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
value class PlatformId(val value: String) {
    companion object {
        val FEISHU = PlatformId("feishu")
        val TELEGRAM = PlatformId("telegram")
        val WEIXIN = PlatformId("weixin")
        val DISCORD = PlatformId("discord")
        val SLACK = PlatformId("slack")
    }

    override fun toString(): String = value
}
