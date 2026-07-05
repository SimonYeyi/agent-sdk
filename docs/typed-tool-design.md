# TypedTool 设计文档

## 背景

Tool 接口的 `execute` 方法接收 `JsonElement`，调用方需要自行处理：
1. JSON 反序列化 → 业务类型
2. 业务类型 → JSON 序列化

对于复杂类型，这部分模板代码枯燥且易错。

## 目标

提供类型安全的 Tool 抽象：自动完成 `JsonElement ↔ typed对象` 转换，业务逻辑在纯 typed 世界。

## 核心概念

### TypeToken

用于携带类型信息，同时获取 serializer。

```kotlin
public data class TypeToken<T : @Serializable Any>(
    val serializer: KSerializer<T>
)

public inline fun <reified T : @Serializable Any> TypeToken(): TypeToken<T> =
    TypeToken(serializer())
```

### TypedTool

纯类型转换，隔离 JsonElement 世界。

```kotlin
public abstract class TypedTool<P : @Serializable Any, R : @Serializable Any>(
    private val parameterType: TypeToken<P>,
    private val resultType: TypeToken<R>
) : Tool {

    abstract override val name: String
    abstract override val description: String

    final override val parametersSchema: ToolParameters =
        ToolParameters.JsonSchema(signatureToJsonSchema())

    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val parameters = Json.decodeFromJsonElement(parameterType.serializer, arguments)
        val result = execute(parameters, context)
        if (result is ToolExecutionResult) return result
        return ToolExecutionResult.success(Json.encodeToString(resultType.serializer, result))
    }

    protected abstract suspend fun execute(parameters: P, context: ToolContext): R
}
```

## 调用链

```
execute(jsonElement)
  ↓ deserialize
execute(request: P, context)  ← 子类实现纯业务逻辑
  ↓ serialize
ToolExecutionResult
```

## Schema 生成

通过 `KSerializer.descriptor` 遍历类型结构，生成标准 JSON Schema。

```kotlin
private fun signatureToJsonSchema(): String {
    val descriptor = parameterType.serializer.descriptor
    val properties = (0 until descriptor.elementsCount).mapNotNull { index ->
        val elementName = descriptor.getElementName(index)
        if (elementName.isEmpty()) return@mapNotNull null
        val elementDescriptor = descriptor.getElementDescriptor(index)
        val kind = elementDescriptor.kind.toString()
        val descAnnotation = descriptor.getElementAnnotations(index)
            .filterIsInstance<Description>()
            .firstOrNull()
        val typePart = if (kind == "ENUM") {
            val enumValues = (0 until elementDescriptor.elementsCount).map {
                elementDescriptor.getElementName(it)
            }
            """"type":"string","enum":[${enumValues.joinToString(",") { "\"$it\"" }}]"""
        } else {
            """"type":"${mapKindToType(kind)}""""
        }
        val descPart = descAnnotation?.let { ""","description":"${it.value}"""" } ?: ""
        """"$elementName":{$typePart$descPart}"""
    }.joinToString(",")
    return """{"type":"object","properties":{$properties}}"""
}
```

**关键点**：
- `descriptor.getElementAnnotations(index)` 可获取字段注解，**不需要反射**
- 这是 kotlinx.serialization 内置 API
- 枚举值通过 `descriptor.getElementName(it)` 获取

## 工厂方法

提供 `tool()` 工厂方法，通过 inline reified 特性自动获取 serializer。

```kotlin
public inline fun <reified P : @Serializable Any, reified R : @Serializable Any> tool(
    name: String,
    description: String,
    noinline execute: suspend (P, ToolContext) -> R
): Tool

// 便捷重载：结果类型为 String
public inline fun <reified P : @Serializable Any> tool(
    name: String,
    description: String,
    noinline execute: suspend (P, ToolContext) -> String
): Tool

// 便捷重载：无参数
public fun tool(
    name: String,
    description: String,
    execute: suspend (Unit, ToolContext) -> String
): Tool
```

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

// 使用工厂方法
val tool = tool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { params, ctx ->
    SendEmailResult(messageId = "123", sentAt = "2024-01-01")
}

// 子类化
class SendEmailTool : TypedTool<EmailRequest, SendEmailResult>(
    parameterType = TypeToken(),
    resultType = TypeToken()
) {
    override val name = "send_email"
    override val description = "发送邮件"

    override suspend fun execute(params: EmailRequest, context: ToolContext): SendEmailResult {
        // 纯 typed 业务逻辑
        return SendEmailResult(messageId = "123", sentAt = "2024-01-01")
    }
}
```

**生成的 JSON Schema**：

```json
{
  "type": "object",
  "properties": {
    "to": {"type": "string", "description": "收件人邮箱"},
    "subject": {"type": "string", "description": "邮件主题"},
    "body": {"type": "string"}
  }
}
```

## Description 注解

用于标注字段描述，自动生成到 schema 中。

```kotlin
@Target(AnnotationTarget.PROPERTY)
@SerialInfo
public annotation class Description(public val value: String)
```

```kotlin
@Serializable
data class EmailRequest(
    @Description("收件人邮箱")
    val to: String
)
```

## 文件结构

```
tool/serialization/src/main/kotlin/io/github/yeyi/agent/tool/serialization/
├── TypeToken.kt          # TypeToken 数据类
├── Description.kt        # @Description 注解
└── TypedTool.kt          # TypedTool 抽象类 + tool() 工厂方法
```

## 与 CompressTool 的关系

```
TypedTool                          # 类型安全 + 标准 JSON Schema
    ↓
CompressTool(TypedTool)            # 叠加 schema 压缩 + execution 解析
```

- TypedTool：生成标准 JSON Schema，适合直接发送给 LLM
- CompressTool：包装 TypedTool，生成压缩的 execution 格式 schema

## oneOf 支持

TypedTool **支持 oneOf** schema 生成。当参数类中有 sealed class 属性时，会自动生成 oneOf 结构。

### 使用方式

```kotlin
@Serializable
sealed class MusicAction {
    @Serializable
    @SerialName("play")
    data class Play(val song: String, val artist: String? = null) : MusicAction()

    @Serializable
    @SerialName("pause")
    data class Pause(val duration: Int? = null) : MusicAction()

    @Serializable
    @SerialName("volume")
    data class Volume(val level: Int) : MusicAction()
}

@Serializable
data class MusicControlRequest(
    @Description("音乐操作")
    val action: MusicAction
)
```

### 生成结果

```json
{
  "type": "object",
  "properties": {
    "action": {
      "oneOf": [
        {"type": {"const": "play"}, "type": "object", "properties": {"song": {...}, "artist": {...}}},
        {"type": {"const": "pause"}, "type": "object", "properties": {"duration": {...}}},
        {"type": {"const": "volume"}, "type": "object", "properties": {"level": {...}}}
      ]
    }
  }
}
```

### 约束

1. sealed class 子类必须加 `@SerialName` 注解指定 discriminator 值
2. 子类为 data class 时支持属性，object 子类暂不支持
3. discriminator 字段名固定为 `"type"`，暂不支持自定义

## 约束

1. `P` 和 `R` 必须加 `@Serializable` 注解
2. TypedTool 自动生成标准 JSON Schema
3. 如需字段描述，在属性上加 `@Description("...")` 注解
4. oneOf 场景：sealed class 属性 + @SerialName 注解

## 功能对比

| 功能 | TypedTool | CompressTool |
|------|-----------|--------------|
| JsonElement → typed | ✅ 自动 | ✅ 自动 |
| typed → JsonElement | ✅ 自动 | ✅ 自动 |
| 标准 JSON Schema | ✅ 自动生成 | ❌ 压缩格式 |
| 字段描述注解 | ✅ 支持 | ❌ 通过压缩格式描述 |
| oneOf 支持 | ✅ sealed class | ✅ 手动 schema |
