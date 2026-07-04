package io.github.yeyi.agent.tool.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * 字段描述注解，用于 TypedTool 自动生成 schema 时提取描述信息。
 *
 * 使用方式：
 * ```kotlin
 * @Serializable
 * data class EmailRequest(
 *     @Description("收件人邮箱")
 *     val to: String
 * )
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@SerialInfo
@OptIn(ExperimentalSerializationApi::class)
public annotation class Description(public val value: String)
