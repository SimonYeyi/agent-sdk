package io.github.yeyi.agent.tool.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * 类型标记，用于携带类型信息同时获取 serializer。
 *
 * @param T 类型
 * @param serializer 用于反序列化 + 序列化
 */
public data class TypeToken<T>(val serializer: KSerializer<T>) {
    public companion object {
        /**
         * 工厂方法，通过 inline reified 特性自动获取 serializer。
         */
        public inline operator fun <reified T> invoke(): TypeToken<T> =
            TypeToken(serializer<T>())
    }
}
