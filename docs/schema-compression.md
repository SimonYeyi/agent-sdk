# Schema 压缩与 Execution 解析模块

## 目标

解决 LLM 调用工具时 JSON Schema 占用大量上下文 token 的问题。

核心思路：
1. 将完整 JSON Schema 压缩为简洁的**函数签名格式**
2. LLM 返回 execution 字符串后，解析还原为结构化 JSON

## 格式规范

### 函数签名格式

```
function_name(
    param1: string | "描述",
    param2?: number,
    param3?: string[] | "描述",
    status: enum(todo, in_progress, done) | "状态",
    config: object
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
| 对象 | `object` | 嵌套结构 |
| 枚举 | `enum(v1, v2, v3)` | 逗号分隔 |
| 参数描述 | `type \| "描述"` | 末尾加 `|` + 引号 |

### execution 格式

LLM 返回：
```json
{
  "name": "send_email",
  "arguments": {
    "execution": "send_email(to='x@x.com', tags=['work'])"
  }
}
```

解析还原后：
```json
{
  "to": "x@x.com",
  "tags": ["work"]
}
```

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
public data class FunctionSignature(
    val name: String,
    val params: List<Param>,
    val description: String? = null
)

public data class Param(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String? = null
)

public sealed class ParamType {
    public data class StringType(val isArray: Boolean = false) : ParamType()
    public data class NumberType(val isArray: Boolean = false) : ParamType()
    public data class BooleanType(val isArray: Boolean = false) : ParamType()
    public data class ObjectType(val isArray: Boolean = false) : ParamType()
    public data class EnumType(val values: List<String>) : ParamType()
}
```

### SignatureCompressor

```kotlin
public interface SignatureCompressor {
    fun compress(schema: String): FunctionSignature
    fun compress(tool: Tool): FunctionSignature
}
```

### ExecutionParser

```kotlin
public interface ExecutionParser {
    fun parse(execution: String, signature: FunctionSignature): JsonElement
}
```

### ToolSchemaMapper

```kotlin
public object ToolSchemaMapper {
    public fun mapToExecutionSchema(
        tool: Tool,
        compressor: SignatureCompressor
    ): Tool
}
```

## 使用方式

```kotlin
val originalTool: Tool = ...
val compressor = DefaultSignatureCompressor()
val parser = DefaultExecutionParser()

// 1. 将 Tool 映射为 execution 格式
val mappedTool = ToolSchemaMapper.mapToExecutionSchema(originalTool, compressor)
val signature = compressor.compress(mappedTool)

// 2. LLM 返回 execution 字符串
val executionStr = "send_email(to='x@x.com', tags=['work'])"

// 3. 解析为结构化 JSON
val jsonArgs = parser.parse(executionStr, signature)
```

## 实现要点

### SignatureCompressor

解析 JSON Schema 字符串，提取：
- 参数名、类型、是否必填
- 枚举值（从 enum 中提取）
- 描述（从 description 字段提取）

忽略：`maxLength`, `pattern`, `format`, `default` 等校验细节。

### ExecutionParser

使用**递归下降 parser** 解析 execution 字符串，状态机处理：
- 引号嵌套：`'it\'s'` 不当成分隔符
- 逗号在引号内：`'John, Smith'`
- 转义字符

### ToolSchemaMapper

将原 Tool 的 parametersSchema 替换为 execution 格式：

```kotlin
// 映射前
parametersSchema: "{ type: 'object', properties: { to: { type: 'string' } } }"

// 映射后
parametersSchema: "{
  type: 'object',
  properties: {
    execution: {
      type: 'string',
      description: 'send_email(to: string, subject: string)'
    }
  },
  required: ['execution']
}"
```

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

工具签名：
```
send_email(to: string, subject: string, body: string, cc?: string[], tags?: string[])
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

## 注意事项

- 必填参数必须提供
- 可空参数用 `?` 标记，可以省略
- 字符串值必须用单引号包裹
- 数组用 `[]`，元素用逗号分隔
- 枚举值直接写，不要加引号
```
