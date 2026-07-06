package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.Serializable

/**
 * 函数签名 — 压缩后的工具参数表示。
 *
 * @param name 函数名
 * @param params 顶层参数列表（用于没有 oneOf 的简单场景）
 * @param branches oneOf 分支列表（用于条件参数场景）
 */
@Serializable
public data class FunctionSignature(
    val name: String,
    val params: List<Param> = emptyList(),
    val branches: List<Branch> = emptyList()
) {
    /**
     * 判断是否为 oneOf 模式
     */
    public val isOneOf: Boolean get() = branches.isNotEmpty()
}

/**
 * oneOf 分支 — 表示一个条件分支及其参数。
 *
 * @param condition 条件表达式，如 `action=play`
 * @param params 该分支下的参数列表
 */
@Serializable
public data class Branch(
    val condition: String,
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

    /** 对象类型。`fields` 非空时携带内层字段结构（递归支持嵌套 object / array of object）。 */
    @Serializable
    public data class ObjectType(
        public val isArray: Boolean = false,
        public val fields: List<Param> = emptyList(),
    ) : ParamType()

    /** 枚举类型。 */
    @Serializable
    public data class EnumType(public val values: List<String>) : ParamType()
}
