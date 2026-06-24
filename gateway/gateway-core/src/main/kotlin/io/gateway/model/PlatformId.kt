package io.gateway.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
public value class PlatformId(public val value: String) {
    public companion object {
        public val FEISHU: PlatformId = PlatformId("feishu")
        public val TELEGRAM: PlatformId = PlatformId("telegram")
        public val WEIXIN: PlatformId = PlatformId("weixin")
        public val DISCORD: PlatformId = PlatformId("discord")
        public val SLACK: PlatformId = PlatformId("slack")
    }

    public override fun toString(): String = value
}
