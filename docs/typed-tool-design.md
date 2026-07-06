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
        ToolParameters.JsonSchema(SchemaGenerator.generateSchema(parameterType.serializer))

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

通过 `KSerializer.descriptor` 递归生成标准 JSON Schema。`SchemaGenerator` 是个 `object`,对外只暴露 `generateSchema(serializer)`,内部按 SerialKind 分发:

| SerialKind | 生成 |
|------------|------|
| `STRING` / `BYTE..DOUBLE` / `BOOLEAN` / `CHAR` | `{"type":"..."}` |
| `ENUM` | `{"type":"string","enum":[...]}` |
| `OBJECT`(Kotlin `object` 声明) | `{"type":"object"}` |
| `LIST` / `SET` | `{"type":"array","items":<递归>}` |
| `STRUCT` / `CLASS`(嵌套 data class) | 递归 `buildObjectSchema` |
| `PolymorphicKind.SEALED` | `{"oneOf":[...]}` |
| `CONTEXTUAL` | 降级 `{"type":"string"}`(实际类型在 schema 生成时不可知) |

**关键点**：
- **递归处理**:任意层级的嵌套 object / list / sealed class 都用同一套逻辑,**不区分顶层和嵌套**
- **`isNullable` → `required` 数组**:可空字段不进 `required`,在每个 object 层(顶层、嵌套 object、oneOf 分支)独立维护
- **`@Description` 注解透传**:`descriptor.getElementAnnotations(index)` 拿注解,**不需要反射**;description 追加在 schema 末尾
- 枚举值通过 `descriptor.getElementName(it)` 获取
- sealed class 走 `PolymorphicKind.SEALED` 路径,内部递归处理子类属性(也走同一套分发)

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
    "to":      {"type": "string", "description": "收件人邮箱"},
    "subject": {"type": "string", "description": "邮件主题"},
    "body":    {"type": "string"}
  },
  "required": ["to", "subject"]
}
```

`body: String?` 是可空字段,不出现在 `required` 数组里。LLM 看到后就知道 `body` 可以省略。

## 嵌套类型支持

任何层级的嵌套结构都按同一套规则生成(不区分顶层和嵌套):

| 嵌套类型 | 生成 |
|----------|------|
| `data class` 字段 | `{"type":"object","properties":{...},"required":[...]}` |
| `List<T>` / `Set<T>` | `{"type":"array","items":<T 的 schema>}` |
| `List<data class>` | `{"type":"array","items":{"type":"object","properties":{...}}}` |
| `List<sealed class>` | `{"type":"array","items":{"oneOf":[...]}}` |
| `sealed class` 字段(任意深度) | `{"oneOf":[...]}` |
| `String?` / `List<X>?` | 字段照常生成,但**不**进父级 `required` |

### 示例:嵌套 data class

```kotlin
@Serializable
data class Address(
    @Description("街道") val street: String,
    val city: String? = null
)

@Serializable
data class NestedRequest(
    @Description("用户名") val name: String,
    val address: Address,
    val tags: List<String>,
    val scores: List<Int>? = null
)
```

生成的 schema:

```json
{
  "type": "object",
  "properties": {
    "name":    {"type": "string", "description": "用户名"},
    "address": {
      "type": "object",
      "properties": {
        "street": {"type": "string", "description": "街道"},
        "city":   {"type": "string"}
      },
      "required": ["street"]
    },
    "tags":    {"type": "array", "items": {"type": "string"}},
    "scores":  {"type": "array", "items": {"type": "number"}}
  },
  "required": ["name", "address", "tags"]
}
```

注意:外层 `required` 排除了 `scores`(可空),内层 `required` 排除了 `city`(可空)。

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
├── SchemaGenerator.kt    # 递归 SerialDescriptor → JSON Schema
└── TypedTool.kt          # TypedTool 抽象类 + tool() 工厂方法
```

## 与 CompressTool 的关系

**两者是工作流上不同环节的独立功能,没有依赖关系。**

- **TypedTool**(在 `tool/serialization` 模块):**Schema 生成**。把 Kotlin 类型(`@Serializable` data class)自动转成标准 JSON Schema,发给 LLM。LLM 看到的就是 `{"type":"object","properties":{...},"required":[...]}` 这样的标准格式。
- **CompressTool**(在 `tool/compression` 模块):**Schema 压缩 + execution 解析**。把任意 Tool 的 JSON Schema 压缩成函数签名格式(`func(a: string, b: number)`),LLM 看到压缩版;它再把 LLM 返回的 execution 字符串解析回原始 JSON 给 Tool 执行。

```
开发期定义类型                 发送给 LLM 的 schema            LLM 返回的内容              喂给 Tool 的内容
TypedTool          ──→       标准 JSON Schema        ──→     JSON 参数          ──→     原始 Tool 接收 JSON
                                                                          
任意 Tool          ──→       CompressTool 装饰后    ──→     execution 字符串   ──→     解析回 JSON,再给原 Tool
                              压缩签名格式
```

- CompressTool 装饰**任何** Tool(不限于 TypedTool),只看 `delegate.parametersSchema` 是不是 JSON Schema
- TypedTool 和 CompressTool 可以**组合使用**:TypedTool 生成的 Tool 再被 CompressTool 装饰,但这不是唯一用法
- 也可以单独用:只用 TypedTool(标准 schema 给 LLM)、或只用 CompressTool(装饰手写 schema 的 Tool)、或都不用(纯 JSON Tool)

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
        {
          "type": "object",
          "properties": {
            "type":   {"const": "play"},
            "song":   {"type": "string"},
            "artist": {"type": "string"}
          },
          "required": ["type", "song"]
        },
        {
          "type": "object",
          "properties": {
            "type":     {"const": "pause"},
            "duration": {"type": "number"}
          },
          "required": ["type"]
        },
        {
          "type": "object",
          "properties": {
            "type":  {"const": "volume"},
            "level": {"type": "number"}
          },
          "required": ["type", "level"]
        }
      ]
    }
  },
  "required": ["action"]
}
```

每个 oneOf 分支自己带 `required`(discriminator + 非空子类字段),顶层 schema 也带 `required`。

### 约束

1. sealed class 子类必须加 `@SerialName` 注解指定 discriminator 值
2. 支持 data class 子类（含属性）和 object 子类
3. discriminator 字段名可通过 `@JsonClassDiscriminator` 自定义，默认 `"type"`
4. 嵌套的 sealed class(在 data class 里、List<sealed> 等)同样支持,生成 `oneOf` 或 `array of oneOf`

## 约束

1. `P` 和 `R` 必须加 `@Serializable` 注解
2. TypedTool 自动生成标准 JSON Schema
3. 如需字段描述，在属性上加 `@Description("...")` 注解
4. oneOf 场景：sealed class 属性 + @SerialName 注解
5. discriminator 字段名可通过 `@JsonClassDiscriminator` 自定义
6. 嵌套 data class / List / Set / List<sealed> / 嵌套 sealed 都自动支持(递归生成)
7. 可空字段通过 `isNullable` 决定是否进入 `required` 数组(类型照常生成)

## 功能对比

**两者解决不同问题,定位对比才有意义:**

| 关注点 | TypedTool | CompressTool |
|--------|-----------|--------------|
| 输入 | Kotlin `@Serializable` 类型 + 业务逻辑 | 任意 `Tool`(其 `parametersSchema`) |
| 输出 | 标准 JSON Schema,直接发给 LLM | 压缩的函数签名格式,发给 LLM |
| LLM 看到的 | 标准 JSON Schema(`{"type":"object","properties":{...}}`) | 压缩签名(`send_email(to: string, subject: string)`) |
| 处理 LLM 返回 | `JsonElement`(LLM 给的 JSON 参数) | `execution` 字符串(LLM 给的函数调用语法) |
| 喂给底层 Tool 的 | 反序列化的 typed 对象 | 解析 execution 还原的 JSON |
| 解决的痛点 | 类型安全 + 消除反序列化模板代码 | 节省 LLM 上下文 token + 解析 LLM 漂移格式 |
