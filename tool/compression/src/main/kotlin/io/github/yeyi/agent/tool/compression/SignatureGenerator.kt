package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.serialization.Description
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * 从 SerialDescriptor 生成 FunctionSignature。
 */
public object SignatureGenerator {

    /**
     * 根据 serializer 的 descriptor 生成函数签名。
     */
    public fun generate(serializer: kotlinx.serialization.KSerializer<*>): FunctionSignature {
        return buildSignature(serializer.descriptor)
    }

    /**
     * 根据 SerialDescriptor 构建 FunctionSignature。
     */
    public fun buildSignature(descriptor: SerialDescriptor): FunctionSignature {
        val params = (0 until descriptor.elementsCount).mapNotNull { index ->
            parseParam(descriptor, index)
        }
        return FunctionSignature(descriptor.serialName, params)
    }

    private fun parseParam(descriptor: SerialDescriptor, index: Int): Param? {
        val name = descriptor.getElementName(index)
        if (name.isEmpty()) return null

        val elementDescriptor = descriptor.getElementDescriptor(index)
        val type = mapType(elementDescriptor.kind.toString(), descriptor)
        val description = getDescription(descriptor, index)

        return Param(name, type, required = true, description = description)
    }

    private fun getDescription(descriptor: SerialDescriptor, index: Int): String? {
        return descriptor.getElementAnnotations(index)
            .filterIsInstance<Description>()
            .firstOrNull()
            ?.value
    }

    private fun mapType(kindStr: String, descriptor: SerialDescriptor): ParamType {
        return when (kindStr) {
            "STRING" -> ParamType.StringType()
            "BYTE", "SHORT", "INT", "LONG" -> ParamType.NumberType()
            "FLOAT", "DOUBLE" -> ParamType.NumberType()
            "BOOLEAN" -> ParamType.BooleanType()
            "CHAR" -> ParamType.StringType()
            "ENUM" -> ParamType.EnumType(
                (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            )
            "LIST" -> ParamType.StringType(isArray = true)
            "MAP", "OBJECT" -> ParamType.ObjectType()
            else -> ParamType.StringType()
        }
    }
}
