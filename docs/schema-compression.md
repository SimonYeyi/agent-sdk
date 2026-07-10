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
    config?: {host: string, port?: number} | "服务器配置"
)
```

### 函数签名格式（oneOf 条件场景）

```
function_name(
    action=play, song: string, artist?: string | "歌手" | "播放一首歌曲";
    action=pause | "暂停播放";
    action=volume, volume: number | "0-100" | "调整音量"
)
```

分支描述追加在分支参数之后(用 `|` + 引号)。每个分支可以有自己的 `description`,告诉模型该分支的语义。

### 嵌套 object / array of object / 嵌套 oneOf

```
query(
    filter: {field: string, op: enum(eq, gt, lt), value?: string} | "过滤条件",
    items?: [{
        id?: number,
        tags?: string[] | "标签数组",
        meta?: {key: string, value?: string} | "自定义元数据"
    }] | "查询结果",
    mode: [
        {type=scan, interval: number | "秒"} | "定时扫描";
        {type=watch, callback: string | "回调 URL"} | "事件监听"
    ] | "执行模式"
)
```

**语法规则**：

| 元素 | 语法 | 说明 |
|------|------|------|
| 必填参数 | `name: type` | — |
| 可选参数 | `name?: type` | 末尾加 `?` |
| 字符串 | `string` | — |
| 数字 | `number` | 整数和浮点统一 |
| 布尔 | `boolean` | — |
| 字符串数组 | `string[]` | — |
| 数字数组 | `number[]` | — |
| 布尔数组 | `boolean[]` | — |
| 对象 | `{k1: type, k2?: type}` | 内联字段结构 |
| 对象数组 | `[{...}]` | array of object |
| 枚举 | `enum(v1, v2, v3)` | 逗号分隔 |
| 参数描述 | `type \| "描述"` | 末尾加 `\|` + 引号 |
| 容器描述 | `{...} \| "描述"` | 嵌套 object / oneOf / array 整体也带描述 |
| 顶层 oneOf 分支 | `cond, params` | 条件参数 + 逗号 + 其他参数 |
| 嵌套 oneOf 分支 | `{cond, params}` | 嵌套场景用 `{}` 包裹分支体 |
| 分支分隔 | `;` | 分号分隔多个分支 |
| catch-all 分支 | `*` | 无判别字段时用 `*` 标识(可省略) |
| 空分支 | `cond` | 只有条件,无其他参数 |

**oneOf 判别字段**:
- 优先 `const`(`{"const": "play"}`),回退单值 `enum`(`{"enum": ["play"]}`)
- 都找不到则该分支为 catch-all(`condition` 为空,渲染为 `*` 或 `{* params}`)
- 条件字段在 execution 中作为第一个参数,如 `action=play`
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
| `boolean[]` | `[true, false]` |
| `object`(无字段) | `{}` |
| `{k1: type, k2?: type}` | `{k1: 'v1', k2: 'v2'}` |
| `[{...}]`(对象数组) | `[{k: 'v'}, {k: 'v'}]` |
| `enum(a, b)` | `a` |
| `[{cond, params}; {cond, params}]` | `[{type=scan, interval=5}]` |

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
    val condition: String,           // 如 "action=play";空字符串表示 catch-all 分支
    val params: List<Param>,
    val description: String? = null  // 分支自身的描述(可选,渲染在分支末尾)
)

@Serializable
public data class Param(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String? = null  // 与 type.description 同值时让 type 独占,避免重复渲染
)

@Serializable
public sealed class ParamType {
    public data class StringType(val isArray: Boolean = false) : ParamType()
    public data class NumberType(val isArray: Boolean = false) : ParamType()
    public data class BooleanType(val isArray: Boolean = false) : ParamType()

    /** 对象类型。`fields` 非空时携带内层字段结构(递归支持嵌套 object / array of object)。 */
    public data class ObjectType(
        val isArray: Boolean = false,
        val fields: List<Param> = emptyList(),
        val description: String? = null,  // 透传内层 schema 的 description(供 array 容器等场景渲染)
    ) : ParamType()

    /**
     * 多分支类型(oneOf / anyOf)。
     * - `condition` 为空字符串的 Branch 表示 catch-all 分支(无判别字段)
     * - `isArray = true` 表示元素是 oneOf(来自 `type: array` + `items: {oneOf: ...}`)
     */
    public data class OneOfType(
        val branches: List<Branch>,
        val isArray: Boolean = false,
        val description: String? = null,
    ) : ParamType()

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
    // 实际压缩后与原 schema 比长度,只有压缩版更短才采用 —— 包装层固定开销 ~100 字符,
    // 且用户编写的 description 不可压缩,对极简 schema / description 占比高的 schema
    // 压缩后反而更长,这种情况下跳过压缩直接返回原 JSON Schema
    // execute 时若跳过压缩则透传 arguments;否则将 execution 字符串还原为原始 JSON 参数
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

解析 JSON Schema 字符串,提取:
- 参数名、类型、是否必填
- 枚举值(从 `enum` 中提取)
- 描述(从 `description` 字段提取,**所有层级都保留**)
- `oneOf` / `anyOf` / `allOf` 分支

**支持的三种模式**:
1. **普通模式**:`properties` 下直接列出所有参数
2. **oneOf 模式**:检测 `oneOf` / `anyOf` 关键字,每个分支通过判别字段区分
3. **allOf 模式**:合并多个分支的 `properties`,`required` 取并集;同名字段取第一个分支(后续不覆盖),不解析 `$ref`

**oneOf vs anyOf**:anyOf 视作 oneOf 处理(同优先级,模型通常不区分语义差别)。

**判别字段查找**:
- 优先 `const`(`{"const": "play"}`)
- 回退单值 `enum`(`{"enum": ["play"]}` 等价 const)
- 都找不到则该分支为 catch-all(`condition` 为空,渲染为 `*`)

**嵌套 oneOf/anyOf/allOf**:
- 嵌套 oneOf 渲染为 `OneOfType`,允许出现在 `ObjectType.fields` 或 array 元素类型里
- 嵌套 allOf 在解析时**先合并为单个 object schema**,再走 `parseObjectFields`

**description 保留规则**(核心设计原则:**不删除用户写的语义内容,只去语法包装和次要约束**):

| 位置 | 行为 |
|------|------|
| Param 描述 | 若与所属的 `ObjectType` / `OneOfType` 描述**同值**,让 type 独占(避免重复渲染);否则两者并存 |
| `ObjectType` / `OneOfType` 描述 | 透传内层 schema 的 `description`,让 array of object、嵌套 oneOf 等场景都能渲染 |
| `Branch` 描述 | 保留分支自身的 `description`,渲染在分支末尾(`{cond, params} \| "branch desc"`) |
| 顶层 oneOf 分支描述 | 同样保留(`action=play, song: string \| "播放歌曲"`) |
| array 容器 vs items | 当外层 description 和 items 的 description **不同**时,两者并存(`impacts?: [{...}] \| "数组说明"`) |

**空 fields 的 ObjectType**:
- 无 `properties` 时不展开为 `{}`,渲染为 `object` / `object[]`
- 若有 `description`,追加在末尾(`object \| "说明"`)

### ExecutionParser

使用**递归下降 parser** 解析 execution 字符串,状态机处理:
- 引号嵌套:`'it\'s'` 不当成分隔符
- 逗号在引号内:`'John, Smith'`
- 转义字符
- 对象字面量:`{key: 'value'}`
- 嵌套 oneOf 判别:`{type=email, to='x@y.com'}` —— 读到判别字段时匹配分支,后续字段用分支的 fields
- 数组字面量:`['a', 'b']`、`[{k: 'v'}]`

**宽容处理**(针对 LLM 漂移,**不影响标准输入**):

| 输入 | 处理 |
|------|------|
| `send_email(to='x', subject='hi')` | 正常解析(带函数名) |
| `to='x@x.com', subject='hi'` | 缺少函数名时也接受,rewind 起点把后续当裸参数列表 |
| `send_email(to : 'x', subject : 'hi')` | 接受 `:` 作为 `=` 的替代分隔符 |
| `to:'x@x.com'` | 键值对之间不加空格也接受 |
| `send_email("x@x.com", "hello")` | Kotlin-style 位置参数(顶层非 oneOf 时生效),按 `signature.params` 顺序映射,多余实参静默丢弃 |
| `f({"Alice", 30})` | 嵌套结构化 object 也接受位置参数,按 `fields` 顺序映射;每层独立检测,外层 named/内层 positional 可混用 |

**判别字段在嵌套 object 中的处理**:
- 取所有非空 `condition` 共享的字段名(通常就是第一个分支的判别字段)
- 解析时按值匹配分支;不匹配则走 catch-all 分支(若存在)

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
| 对象 | `{key: 'value', k2: v2}` | `{field: 'status', op: 'eq'}` |
| 对象数组 | `[{k: 'v'}]` | `[{id: 1, name: 'a'}]` |
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

### 嵌套对象工具

工具签名：
```
query(filter: {field: string, op: enum(eq, gt, lt), value?: string}, limit?: number)
```

正确返回：
```json
{
  "name": "query",
  "arguments": {
    "execution": "query(filter={field='status', op='eq', value='active'}, limit=10)"
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
- 可选参数用 `?` 标记,可以省略
- 字符串值必须用单引号包裹
- 数组用 `[]`,元素用逗号分隔
- 对象用 `{}`,字段顺序不影响解析
- 枚举值直接写,不要加引号
- oneOf 分支用 `;` 分隔,模型根据条件字段(如 `action`)值决定传哪些参数
- 描述(` \| "..."`)是给模型读的参考,按需提供即可;不会被解析器消费

## 兼容性说明

解析器对以下 LLM 漂移做了兼容,**正常输入不受影响**:

| 漂移 | 兼容方式 |
|------|----------|
| 漏写函数名和括号 | `a=1, b=2` 与 `f(a=1, b=2)` 等价 |
| 漏写 `=` 写 `:` | `a : 1` 与 `a = 1` 等价 |
| 键值无空格 | `a:1` 接受 |
| Kotlin-style 位置参数 | `f("x", 30)` 与 `f(arg1="x", arg2=30)` 等价;按 `params` 顺序赋值,多余实参静默丢弃;也适用于嵌套结构化 object 的 `fields`;oneOf 暂不支持 |

## 设计原则:不删用户内容

压缩的目标是**节省 token**,但节省的方式是去除**语法包装**(JSON 嵌套、`required` 数组、`type` 字段)和**次要约束**(`min`/`max`/`pattern`/`format`),**绝不删除用户写的语义内容**(尤其是 `description` 类的元信息)。

理由:用户写 `description` 是为了把领域知识传给 LLM,压缩器擅自删除等于剥夺用户的控制权。如果用户觉得 description 多余,正确的做法是让他自己不写,而不是压缩器替他删。
