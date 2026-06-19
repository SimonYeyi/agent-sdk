# MCP Capabilities 与 Notifications 实现

> 日期: 2026-06-19
> 状态: 实现中

---

## 1. 背景

MCP 模块在协议合规性审计中发现两个问题：

1. **Capabilities 未实现**: `InitializeParams.capabilities` 和 `InitializeResult.capabilities` 当前都是 `JsonObject(emptyMap())`，没有定义具体类型
2. **Notifications 流程未实现**: `StdioTransport` 和 `SseTransport` 的 `notifications` 属性都返回 `emptyFlow()`

本次修改旨在完整支持 MCP 2025-06-18 协议规范。

---

## 2. Capabilities 实现

### 2.1 协议分析

根据 MCP 协议规范：
- `ClientCapabilities` 告知服务端客户端支持哪些可选功能
- `ServerCapabilities` 是服务端返回的其支持的功能列表
- **空对象 `{}` 表示支持该功能**，缺失表示不支持

### 2.2 ClientCapabilities（发送）

```kotlin
// McpServer.kt
@Serializable
public data class ClientCapabilities(
    val roots: RootsObject? = null,
    // sampling: SamplingObject（单例）= 支持，null = 不支持
    val sampling: SamplingObject? = null,
    val elicitation: ElicitationObject? = null,
    val experimental: JsonObject? = null,
)

// roots: 有 listChanged 字段，不是空对象，所以用 data class
// listChanged: boolean, 可空
@Serializable
public data class RootsObject(
    val listChanged: Boolean? = null,
)

// sampling/elicitation: 空对象 {} 表示支持该功能，用 object class
@Serializable
public object SamplingObject
@Serializable
public object ElicitationObject
```

**注意**: `sampling: {}` 表示支持 sampling（空对象），不是 null。需要用 object class 表示。

### 2.3 ServerCapabilities（接收）

```kotlin
@Serializable
public data class ServerCapabilities(
    val logging: LoggingObject? = null,
    val completions: CompletionsObject? = null,
    val tools: ToolsObject? = null,
    val resources: ResourcesObject? = null,
    val prompts: PromptsObject? = null,
    val sampling: SamplingObject? = null,  // ✅ 补上
    val experimental: JsonObject? = null,
)

@Serializable
public object LoggingObject
@Serializable
public object CompletionsObject
@Serializable
public object SamplingObject  // ✅ 补上，与 ClientCapabilities 共用

@Serializable
public data class ToolsObject(
    val listChanged: Boolean? = null,
)

@Serializable
public data class ResourcesObject(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null,
)

@Serializable
public data class PromptsObject(
    val listChanged: Boolean? = null,
)
```

### 2.4 修改现有类型

| 类型 | 修改内容 |
|------|---------|
| `InitializeParams.capabilities` | `JsonObject` → `ClientCapabilities` |
| `InitializeResult.capabilities` | `JsonObject` → `ServerCapabilities` |

### 2.5 GenericMcpServer 修改

发送时声明客户端 capabilities：
```kotlin
val params = InitializeParams(
    protocolVersion = SUPPORTED_PROTOCOL_VERSION,
    capabilities = ClientCapabilities(
        roots = RootsObject(listChanged = true),  // 需要监听 listChanged 时启用
        sampling = SamplingObject,                // 支持 sampling
        elicitation = ElicitationObject,          // 支持 elicitation
    ),
    clientInfo = clientInfo,
)
```

接收时解析服务端 capabilities：
```kotlin
val result = json.decodeFromJsonElement<InitializeResult>(resultElement)
// 检查服务端支持哪些功能
val toolsSupported = result.capabilities.tools != null
val resourcesListChanged = result.capabilities.resources?.listChanged == true
```

---

## 3. Notifications 流程实现

### 3.1 协议分析

根据 MCP 协议文档：

**Server → Client 通知通用结构**：
```json
{
  "jsonrpc": "2.0",
  "method": "<method_name>",
  "params": <object_or_null>
}
```

**前置条件**：Server → Client 通知需要对应 capability 声明才可发送。例如 `tools/list_changed` 需要 `capabilities.tools.listChanged: true`。

| Method | Direction | params | 说明 |
|--------|-----------|--------|------|
| `notifications/message` | Server → Client | `{level, logger?, data?}` | 日志消息 |
| `notifications/progress` | Server → Client | `{progressToken, progress, total?}` | 进度通知 |
| `notifications/tools/list_changed` | Server → Client | null | 工具列表变更 |
| `notifications/resources/list_changed` | Server → Client | null | 资源列表变更 |
| `notifications/resources/updated` | Server → Client | `{uri}` | 资源内容变更 |
| `notifications/prompts/list_changed` | Server → Client | null | Prompt列表变更 |
| `notifications/initialized` | **Client → Server** | null | 初始化完成（GenericMcpServer 握手步骤3） |

### 3.2 新增 Notification Params 数据类

```kotlin
// McpServer.kt

@Serializable
public enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
public data class MessageNotificationParams(
    val level: LogLevel,          // enum: debug/info/warning/error, 不可空
    val logger: String? = null,  // 可空
    val data: JsonElement? = null // 可空
)

@Serializable
public data class ProgressNotificationParams(
    val progressToken: String,   // 必须与请求中的 _meta.progressToken 一致
    val progress: Double,        // 当前进度，从0开始，number 类型用 Double
    val total: Double? = null   // 可空，null表示不确定进度
)

@Serializable
public data class ResourcesUpdatedNotificationParams(
    val uri: String,  // 不可空
)

// notifications/tools/list_changed 等无 params，用 EmptyNotificationParams
```

### 3.3 StdioTransport 实现

stdio 模式下，服务端通过 stdout 发送 notification。修改 `send()` 方法：

```kotlin
class StdioTransport : McpTransport {
    private val _notifications = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val notifications: Flow<JsonRpcNotification<JsonElement>> = _notifications.asSharedFlow()

    // 修改 send() 中的响应读取逻辑
    private suspend fun readResponseLine(reader: BufferedReader, expectedId: Int): JsonRpcResponse<JsonElement> {
        val line = reader.readLine() ?: throw RuntimeException("MCP server process terminated")
        val parsed = json.parseToJsonElement(line)
        
        // 检查是否是 notification（无 id 字段）
        if (parsed is JsonObject && parsed["id"] == null) {
            val notification = json.decodeFromJsonElement<JsonRpcNotification<JsonElement>>(parsed)
            _notifications.emit(notification)
            // 递归读取下一行，直到找到匹配的 response
            return readResponseLine(reader, expectedId)
        }
        
        // 有 id，是 response
        val response = json.decodeFromJsonElement<JsonRpcResponse<JsonElement>>(parsed)
        if (response.id != expectedId) {
            throw RuntimeException("MCP response ID mismatch: expected $expectedId, got ${response.id}")
        }
        return response
    }
}
```

### 3.4 SseTransport 实现

根据 MCP Streamable HTTP 协议：
- **POST**: 发送请求，接收响应（可能是 JSON 或 SSE 流）
- **GET**: 单独的 SSE 流，接收 server-to-client notifications

**当前 SSE 响应解析的问题**：POST 响应中的 SSE 流可能包含 progress 通知，这些通知有 `method` 字段但 id 与请求不匹配。需要将 progress 事件 emit 到 notifications flow。

```kotlin
class SseTransport : McpTransport {
    private val _notifications = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val notifications: Flow<JsonRpcNotification<JsonElement>> = _notifications.asSharedFlow()

    // progress 通知需要与请求中的 progressToken 匹配
    // 当前实现：将 progress 通知 emit 到 notifications flow
    private fun parseSseResponse(body: String, expectedId: Int): JsonRpcResponse<JsonElement> {
        for (event in SseEvent.parseAll(body)) {
            val data = event.data
            if (data.isEmpty() || data == "[DONE]") continue
            
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: continue
            val obj = element as? JsonObject ?: continue
            
            // 有 method 字段但 id 不匹配 → 这是 notification
            if (obj["method"] != null) {
                val idElement = obj["id"] as? JsonPrimitive
                val matches = idElement?.intOrNull == expectedId ||
                        idElement?.content == expectedId.toString()
                if (!matches) {
                    // 是 notification，emit 到 flow
                    val notification = json.decodeFromJsonElement<JsonRpcNotification<JsonElement>>(element)
                    _notifications.emit(notification)
                    continue
                }
            }
            
            // 有 id 且匹配 → 是最终响应
            if (obj["id"] != null) {
                val idElement = obj["id"] as? JsonPrimitive
                val matches = idElement?.intOrNull == expectedId ||
                        idElement?.content == expectedId.toString()
                if (matches) {
                    return json.decodeFromJsonElement(
                        JsonRpcResponse.serializer(JsonElement.serializer()),
                        element,
                    )
                }
            }
        }
        throw RuntimeException("No matching response in SSE stream for id=$expectedId")
    }
}
```

### 3.5 SseTransport GET 通知通道

当 sessionId 获取后，启动单独的 GET 连接监听 notifications：

```kotlin
class SseTransport : McpTransport {
    private var notificationJob: Job? = null
    
    private fun startNotificationChannel(sessionId: String) {
        notificationJob = scope.launch {
            client.get(endpoint) {
                header(MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                header(MCP_SESSION_ID_HEADER, sessionId)
                accept(ContentType.Text.EventStream)
            }.bodyAsText { channel ->
                // 持续读取 SSE 事件并 emit 到 _notifications
            }
        }
    }
}
```

### 3.6 GenericMcpServer 处理 listChanged

```kotlin
class GenericMcpServer {
    private var toolsCache: ListToolsResult? = null

    init {
        // 启动时订阅 notifications
        scope.launch {
            transport.notifications.collect { notification ->
                handleNotification(notification)
            }
        }
    }

    private fun handleNotification(notification: JsonRpcNotification<JsonElement>) {
        when (notification.method) {
            McpMethods.NOTIFICATIONS_TOOLS_LIST_CHANGED -> {
                toolsCache = null  // 失效缓存
            }
            McpMethods.NOTIFICATIONS_MESSAGE -> {
                // 可选：记录日志
            }
            McpMethods.NOTIFICATIONS_PROGRESS -> {
                // 可选：处理进度（需要 progressToken 匹配）
            }
        }
    }
}
```

---

## 4. McpMethods 新增

```kotlin
public object McpMethods {
    // ... 已有
    public const val NOTIFICATIONS_MESSAGE: String = "notifications/message"
    public const val NOTIFICATIONS_PROGRESS: String = "notifications/progress"
    public const val NOTIFICATIONS_RESOURCES_LIST_CHANGED: String = "notifications/resources/list_changed"
    public const val NOTIFICATIONS_RESOURCES_UPDATED: String = "notifications/resources/updated"
    public const val NOTIFICATIONS_PROMPTS_LIST_CHANGED: String = "notifications/prompts/list_changed"
}
```

---

## 5. 涉及文件

| 文件 | 修改内容 |
|------|---------|
| `McpServer.kt` | 新增 capabilities 数据类、notification params；修改 InitializeParams/InitializeResult 类型；新增 McpMethods 常量 |
| `GenericMcpServer.kt` | 使用强类型 capabilities；订阅 notifications 处理 listChanged |
| `StdioTransport.kt` | 实现 MutableSharedFlow；修改 send() 区分 response/notification |
| `SseTransport.kt` | 实现 MutableSharedFlow；修改 SSE 解析以 emit progress 通知；实现 GET 通知通道 |
| `McpTransport.kt` | 更新文档注释 |

---

## 6. 设计原则

1. **结果不带默认值**: 响应类型所有字段均无默认值，严格对应协议
2. **入参可空字段**: 用 `?` 标记可空，允许 null
3. **空对象表示支持**: `logging: {}` 表示支持该功能，用 object class 表示
4. **单向 notification**: `notifications` 是 server-to-client 的 Flow，不期待响应
5. **BufferOverflow 处理**: notification buffer 满时 DROP_OLDEST，避免阻塞正常流程

---

## 7. 验证方式

1. `./gradlew :mcp:test` - 现有测试通过
2. 添加 capabilities 序列化/反序列化测试
3. 添加 notification 流程测试（mock transport/reader）
