# Schema 压缩与 Execution 解析模块

## 目标

解决 LLM 调用工具时 JSON Schema 占用大量上下文 token 的问题。

核心思路：
1. 将完整 JSON Schema 压缩为简洁的**函数签名格式**
2. LLM 返回 execution 字符串后，解析还原为结构化 JSON

## 格式规范

### 函数签名格式（普通场景）

```
function_name(
    param1: string | "描述",
    param2?: number,
    param3?: string[] | "描述",
    status: enum(todo, in_progress, done) | "状态",
    config: object
)
```

### 函数签名格式（oneOf 条件场景）

```
function_name(
    action=play, song: string, artist?: string | "歌手";
    action=pause;
    action=volume, volume: number | "0-100"
)
```

**语法规则**：

| 元素 | 语法 | 说明 |
|------|------|------|
| 必填参数 | `name: type` | — |
| 可空参数 | `name?: type` | 末尾加 `?` |
| 字符串 | `string` | — |
| 数字 | `number` | 整数和浮点统一 |
| 布尔 | `boolean` | — |
| 字符串数组 | `string[]` | — |
| 数字数组 | `number[]` | — |
| 对象 | `object` | 嵌套结构 `{key: value}` |
| 枚举 | `enum(v1, v2, v3)` | 逗号分隔 |
| 参数描述 | `type \| "描述"` | 末尾加 `\|` + 引号 |
| oneOf 分支 | `cond, params` | 条件参数 + 逗号 + 其他参数 |
| 分支分隔 | `;` | 分号分隔多个分支 |
| 空分支 | `cond` | 只有条件，无其他参数 |

**oneOf 说明**：
- 每个分支有一个共同的条件字段（如 `action`），通过 `const` 值区分
- 条件字段在 execution 中作为第一个参数，如 `action=play`
- 解析时根据条件字段的值找到对应分支

### execution 格式

LLM 返回：
```json
{
  "name": "music_control",
  "arguments": {
    "execution": "music_control(action=play, song='海阔天空')"
  }
}
```

解析还原后：
```json
{
  "action": "play",
  "song": "海阔天空"
}
```

### oneOf execution 格式

```
music_control(action=play, song='海阔天空')
music_control(action=volume, volume=75)
music_control(action=pause)
```

**注意**：oneOf 的 execution 格式和普通格式完全一样，模型无需特殊处理。分支信息只在 schema 描述中体现，告诉模型每种条件需要哪些参数。

## 类型映射

| 签名类型 | execution 返回示例 |
|----------|-------------------|
| `string` | `'hello'` |
| `number` | `123` / `3.14` |
| `boolean` | `true` |
| `string[]` | `['a', 'b']` |
| `number[]` | `[1, 2, 3]` |
| `object` | `{key: 'value'}` |
| `enum(a, b)` | `a` |

## 核心接口

### FunctionSignature

```kotlin
@Serializable
public data class FunctionSignature(
    val name: String,
    val params: List<Param> = emptyList(),  // 普通模式参数
    val branches: List<Branch> = emptyList() // oneOf 模式分支
) {
    public val isOneOf: Boolean get() = branches.isNotEmpty()
}

@Serializable
public data class Branch(
    val condition: String,  // 如 "action=play"
    val params: List<Param>
)

@Serializable
public data class Param(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String? = null
)

@Serializable
public sealed class ParamType {
    public data class StringType(val isArray: Boolean = false) : ParamType()
    public data class NumberType(val isArray: Boolean = false) : ParamType()
    public data class BooleanType(val isArray: Boolean = false) : ParamType()
    public data class ObjectType(val isArray: Boolean = false) : ParamType()
    public data class EnumType(val values: List<String>) : ParamType()
}
```

### SchemaCompressor

```kotlin
public interface SchemaCompressor {
    fun compress(name: String, schema: String): CompressionResult
}

public data class CompressionResult(
    val compressedSchema: String,
    val signature: FunctionSignature
)
```

### CompressTool

```kotlin
public class CompressTool(private val delegate: Tool) : Tool {
    // 装饰原 Tool，将 parametersSchema 替换为压缩后的 execution 格式
    // execute 时将 execution 字符串还原为原始 JSON 参数
}
```

### ExecutionParser

```kotlin
public interface ExecutionParser {
    fun parse(execution: String, signature: FunctionSignature): JsonElement
}
```

## 使用方式

### 普通工具

```kotlin
// 原始 tool
val originalTool: Tool = tool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { ... }

// 用 CompressTool 包装，自动压缩 schema 并解析 execution
val compressedTool = CompressTool(originalTool)

// LLM 看到的 schema 是：
// send_email(to: string, subject: string, body?: string | "可选")

// LLM 返回 execution 字符串
val executionStr = "send_email(to='x@x.com', subject='hello')"

// CompressTool.execute() 自动解析为原始 JSON
val jsonArgs = compressedTool.execute(arguments, context)
// jsonArgs = {"to": "x@x.com", "subject": "hello"}
```

### oneOf 条件工具

```kotlin
// JSON Schema 使用 oneOf 描述条件参数
val schema = """
{
    "oneOf": [
        {
            "properties": {
                "action": {"const": "play"},
                "song": {"type": "string"}
            },
            "required": ["action", "song"]
        },
        {
            "properties": {
                "action": {"const": "volume"},
                "volume": {"type": "integer"}
            },
            "required": ["action", "volume"]
        }
    ]
}
"""

val result = compressor.compress("music_control", schema)
// result.signature.branches 包含多个分支
// result.compressedSchema 格式如：
// music_control(action=play: song: string; action=volume: volume: number)
```

## 实现要点

### SchemaCompressor

解析 JSON Schema 字符串，提取：
- 参数名、类型、是否必填
- 枚举值（从 enum 中提取）
- 描述（从 description 字段提取）
- oneOf 分支（从 oneOf 数组提取）

支持两种模式：
1. **普通模式**：properties 下直接列出所有参数
2. **oneOf 模式**：检测 oneOf 关键字，每个分支通过 const 字段区分

### ExecutionParser

使用**递归下降 parser** 解析 execution 字符串，状态机处理：
- 引号嵌套：`'it\'s'` 不当成分隔符
- 逗号在引号内：`'John, Smith'`
- 转义字符
- 对象字面量：`{key: 'value'}`
- oneOf 分支：`action=play, song='x'; action=volume, volume=75`

## 语法教学 System Prompt

```
你是一个助手。当用户要求调用工具时，请按以下格式返回：

## 工具调用格式

每个工具调用需要返回一个 JSON 对象，包含 name 和 arguments：

```json
{
  "name": "工具名称",
  "arguments": {
    "execution": "工具名(参数1='值1', 参数2=['数组值'], 参数3=枚举值)"
  }
}
```

## 参数类型规则

| 类型 | 格式 | 示例 |
|------|------|------|
| 字符串 | `'值'` | `'hello'` |
| 数字 | 直接数字 | `123` |
| 布尔 | `true`/`false` | `true` |
| 字符串数组 | `['a', 'b']` | `['work', 'urgent']` |
| 数字数组 | `[1, 2]` | `[1, 2, 3]` |
| 枚举 | 枚举值之一 | `active` |

## 完整示例

### 普通工具

工具签名：
```
send_email(to: string, subject: string, body?: string, cc?: string[], tags?: string[])
```

正确返回：
```json
{
  "name": "send_email",
  "arguments": {
    "execution": "send_email(to='user@example.com', subject='会议邀请', body='明天下午3点开会', tags=['meeting'])"
  }
}
```

### oneOf 条件工具

工具签名：
```
music_control(action=play, song: string, artist?: string; action=pause; action=volume, volume: number)
```

正确返回（播放）：
```json
{
  "name": "music_control",
  "arguments": {
    "execution": "music_control(action=play, song='海阔天空', artist='Beyond')"
  }
}
```

正确返回（暂停）：
```json
{
  "name": "music_control",
  "arguments": {
    "execution": "music_control(action=pause)"
  }
}
```

正确返回（音量）：
```json
{
  "name": "music_control",
  "arguments": {
    "execution": "music_control(action=volume, volume=75)"
  }
}
```

## 注意事项

- 必填参数必须提供
- 可空参数用 `?` 标记，可以省略
- 字符串值必须用单引号包裹
- 数组用 `[]`，元素用逗号分隔
- 枚举值直接写，不要加引号
- oneOf 分支用 `;` 分隔，模型根据条件字段（如 action）值决定传哪些参数
