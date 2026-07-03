package io.github.yeyi.agent.schema

import kotlinx.serialization.Serializable

/**
 * 函数签名 — 压缩后的工具参数表示。
 *
 * @param name 函数名
 * @param params 参数列表
 */
@Serializable
public data class FunctionSignature(
    val name: String,
    val params: List<Param>
)

/**
 * 参数定义。
 *
 * @param name 参数名
 * @param type 参数类型
 * @param required 是否必填
 * @param description 参数描述（可选）
 */
@Serializable
public data class Param(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String? = null
)

/**
 * 参数类型。
 */
@Serializable
public sealed class ParamType {
    /** 字符串类型。 */
    @Serializable
    public data class StringType(public val isArray: Boolean = false) : ParamType()

    /** 数字类型（整数和浮点统一）。 */
    @Serializable
    public data class NumberType(public val isArray: Boolean = false) : ParamType()

    /** 布尔类型。 */
    @Serializable
    public data class BooleanType(public val isArray: Boolean = false) : ParamType()

    /** 对象类型。 */
    @Serializable
    public data class ObjectType(public val isArray: Boolean = false) : ParamType()

    /** 枚举类型。 */
    @Serializable
    public data class EnumType(public val values: List<String>) : ParamType()
}
