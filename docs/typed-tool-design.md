# TypedTool / CompressibleTool 设计文档

## 背景

当前 Tool 接口的 execute 方法接收 `JsonElement`，调用方需要自行处理：
1. JSON 反序列化 → 业务类型
2. 业务类型 → JSON 序列化

对于复杂类型，这部分模板代码枯燥且易错。

## 目标

提供两层抽象：

1. **TypedTool**：自动完成 `JsonElement ↔ typed对象` 转换，业务逻辑在纯 typed 世界
2. **CompressibleTool**：在 TypedTool 基础上叠加 schema 压缩，LLM 用 execution 字符串调用

## 设计

### TypeToken

用于携带类型信息，同时获取 serializer。

```kotlin
package io.github.yeyi.agent.schema

import kotlin.reflect.KType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

data class TypeToken<T : @Serializable Any>(
    val kType: KType,
    val serializer: KSerializer<T>
)

inline fun <reified T : @Serializable Any> typeToken(): TypeToken<T> =
    TypeToken(typeOf<T>(), serializer<T>())
```

### Layer 1: TypedTool

纯类型转换，隔离 JsonElement 世界。

```kotlin
public abstract class TypedTool<in P : @Serializable Any, out R : @Serializable Any>(
    public val parameterType: TypeToken<P>,
    public val resultType: TypeToken<R>
) : Tool {

    abstract override val name: String
    abstract override val description: String
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    open suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val request = parameterType.serializer.deserialize(arguments)
        val response = execute(request, context)
        return ToolExecutionResult.success(resultType.serializer.serialize(response))
    }

    protected abstract suspend fun execute(request: P, context: ToolContext): R
}
```

### Layer 2: CompressibleTool

继承 TypedTool，叠加 execution 字符串解析和 schema 压缩。

```kotlin
public abstract class CompressibleTool<in P : @Serializable Any, out R : @Serializable Any>(
    parameterType: TypeToken<P>,
    resultType: TypeToken<R>
) : TypedTool<P, R>(parameterType, resultType) {

    private val signature: FunctionSignature
    private val compressor = DefaultSignatureCompressor()

    init {
        signature = buildSignature(parameterType.serializer.descriptor)
    }

    override val parametersSchema: ToolParameters
        get() = ToolParameters.JsonSchema(buildCompressedSchema(signature))

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val originalArgs = extractArguments(arguments, signature)
        return super.execute(originalArgs, context)
    }

    protected abstract override suspend fun execute(request: P, context: ToolContext): R
}
```

## 调用链

### TypedTool

```
execute(jsonElement)
  ↓ deserialize
execute(request: P, context)  ← 子类实现纯业务逻辑
  ↓ serialize
ToolExecutionResult
```

### CompressibleTool

```
execute(execution_string)
  ↓ extractArguments → parse execution string → original JSON
super.execute(original_json)
  ↓ deserialize
execute(request: P, context)  ← 子类实现纯业务逻辑
  ↓ serialize
ToolExecutionResult
```

## Schema 生成

通过 `KSerializer.descriptor` 遍历类型结构，生成函数签名。

```kotlin
@Target(AnnotationTarget.PROPERTY)
annotation class Description(val value: String)

private fun buildSignature(descriptor: SerialDescriptor): FunctionSignature {
    val params = (0 until descriptor.elementsCount).mapNotNull { index ->
        val elementDescriptor = descriptor.getElementDescriptor(index)
        val name = descriptor.getElementName(index)
        val type = mapType(elementDescriptor.kind, elementDescriptor)
        val isOptional = !descriptor.isElementRequired(index)
        val desc = descriptor.getElementAnnotations(index)
            .filterIsInstance<Description>()
            .firstOrNull()
            ?.value
        Param(name, type, !isOptional, desc)
    }
    return FunctionSignature(name, params)
}

private fun mapType(kind: SerialKind, descriptor: SerialDescriptor): ParamType = when (kind) {
    is PrimitivesKind.STRING -> ParamType.StringType()
    is PrimitivesKind.INT, is PrimitivesKind.LONG,
    is PrimitivesKind.FLOAT, is PrimitivesKind.DOUBLE -> ParamType.NumberType()
    is PrimitivesKind.BOOLEAN -> ParamType.BooleanType()
    is SerialKind.ENUM -> ParamType.EnumType(
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
    )
    is StructureKind.LIST, is StructureKind.COLLECTION -> ParamType.StringType(isArray = true)
    is StructureKind.OBJECT -> ParamType.ObjectType()
    else -> ParamType.StringType()
}
```

**关键点**：
- `descriptor.getElementAnnotations(index)` 可获取字段注解，**不需要反射**
- 这是 kotlinx.serialization 内置 API，直接可用
- 枚举值通过 `descriptor.getElementName(it)` 获取

## 使用示例

```kotlin
@Serializable
data class EmailRequest(
    @Description("收件人邮箱")
    val to: String,
    @Description("邮件主题")
    val subject: String,
    val body: String? = null
)

@Serializable
data class SendEmailResult(
    val messageId: String,
    val sentAt: String
)

@Serializable
enum class Status {
    TODO, IN_PROGRESS, DONE
}

// 不压缩的 Tool
class SendEmailTool : TypedTool<EmailRequest, SendEmailResult>(
    parameterType = typeToken<EmailRequest>(),
    resultType = typeToken<SendEmailResult>()
) {
    override val name = "send_email"
    override val description = "发送邮件"

    override suspend fun execute(request: EmailRequest, context: ToolContext): SendEmailResult {
        // 纯 typed 业务逻辑，不需要 JsonElement
        return SendEmailResult(messageId = "123", sentAt = "2024-01-01")
    }
}

// 压缩的 Tool
class SendEmailTool2 : CompressibleTool<EmailRequest, SendEmailResult>(
    parameterType = typeToken<EmailRequest>(),
    resultType = typeToken<SendEmailResult>()
) {
    override val name = "send_email"
    override val description = "发送邮件"

    override suspend fun execute(request: EmailRequest, context: ToolContext): SendEmailResult {
        // 纯 typed 业务逻辑
    }
}
```

**生成的压缩 schema**：

```json
{
  "type": "object",
  "properties": {
    "execution": {
      "type": "string",
      "description": "send_email(to: string | \"收件人邮箱\", subject: string | \"邮件主题\", body?: string)"
    }
  },
  "required": ["execution"]
}
```

## 文件结构

```
schema/src/main/kotlin/io/github/yeyi/agent/schema/
├── TypeToken.kt              # TypeToken 数据类 + typeToken() 工厂方法
├── Description.kt            # @Description 注解，供字段描述使用
├── TypedTool.kt              # TypedTool 抽象类
├── CompressibleTool.kt       # CompressibleTool 抽象类
├── SignatureGenerator.kt     # 从 SerialDescriptor 生成 FunctionSignature
└── CompressTool.kt           # 保留，用于 extractArguments
```

## 依赖

```kotlin
// schema/build.gradle.kts
dependencies {
    api(project(":agent"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.core)
}
```

## 约束

1. `P` 和 `R` 必须加 `@Serializable` 注解
2. CompressibleTool 的 schema 由 serializer 自动生成
3. 如需字段描述，在属性上加 `@Description("...")` 注解
4. CompressibleTool 依赖 CompressTool.extractArguments 解析 execution 字符串

## 功能对比

| 功能 | TypedTool | CompressibleTool |
|---|---|---|
| JsonElement → typed | ✅ 自动 | ✅ 自动 |
| typed → JsonElement | ✅ 自动 | ✅ 自动 |
| schema 压缩 | ❌ 原样 | ✅ 自动生成 |
| execution 解析 | ❌ 无 | ✅ 自动 |
| 字段描述注解 | ❌ 不支持 | ✅ 支持 |
