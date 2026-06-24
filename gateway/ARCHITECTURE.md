# Gateway 模块架构设计

## 1. 模块结构

```
gateway/
├── gateway-core/                      # 核心模块（零平台依赖）
│   ├── api/                          # 公开接口
│   │   ├── PlatformAdapter.kt         # 平台适配器接口
│   │   ├── AgentRunner.kt            # AI 处理器 SPI
│   │   ├── SessionManager.kt          # 会话管理 SPI
│   │   ├── HookPipeline.kt            # Hook 管道接口
│   │   ├── GatewayEngine.kt          # 网关引擎接口
│   │   ├── ConcurrencyController.kt   # 并发控制接口
│   │   └── DeliveryRouter.kt         # 发送路由接口
│   │
│   ├── model/                        # 数据模型
│   │   ├── PlatformId.kt             # 平台标识 (value class)
│   │   ├── GatewaySession.kt          # 会话数据（避免与用户 Session 混淆）
│   │   ├── IncomingMessage.kt        # 入站消息
│   │   ├── OutgoingMessage.kt        # 出站消息
│   │   ├── MessageContent.kt         # sealed class 统一消息类型
│   │   ├── ConnectionState.kt         # 连接状态
│   │   ├── SendResult.kt             # 发送结果
│   │   └── ...
│   │
│   ├── engine/                        # 默认实现
│   │   ├── DefaultGatewayEngine.kt   # 核心引擎
│   │   ├── FileSessionManager.kt     # 文件存储会话（JSONL）
│   │   ├── InMemorySessionManager.kt # 内存会话
│   │   ├── DefaultDeliveryRouter.kt   # 默认发送路由
│   │   ├── DefaultHookPipeline.kt    # 默认 Hook 管道
│   │   ├── DefaultConcurrencyController.kt
│   │   └── GatewayEngineBuilder.kt   # 构建器
│   │
│   ├── hook/                         # 内置 Hook
│   │   ├── WhitelistHook.kt          # 白名单过滤
│   │   ├── RateLimitHook.kt          # 速率限制
│   │   └── LoggingHook.kt           # 日志记录
│   │
│   └── util/                         # 工具类
│       ├── MessageDeduplicator.kt    # 消息去重
│       └── MarkdownUtils.kt          # Markdown 工具
│
└── platforms/                        # 平台适配器（独立模块）
    ├── feishu/                       # 飞书适配器
    │   ├── FeishuAdapter.kt         # 主适配器
    │   ├── FeishuWebSocketClient.kt # WebSocket 客户端（自行实现）
    │   ├── FeishuMessageParser.kt   # 消息解析
    │   ├── FeishuMessageSender.kt    # 消息发送
    │   └── FeishuConfig.kt          # 配置
    │
    ├── telegram/                      # Telegram 适配器
    │   ├── TelegramAdapter.kt
    │   ├── TelegramPoller.kt         # 长轮询
    │   ├── TelegramMessageParser.kt
    │   ├── TelegramMessageSender.kt
    │   └── TelegramConfig.kt
    │
    └── weixin/                        # 微信适配器
        ├── WeixinAdapter.kt
        ├── WeixinPoller.kt           # 长轮询
        ├── WeixinMessageParser.kt
        ├── WeixinMessageSender.kt
        └── WeixinConfig.kt
```

## 2. 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| **网络框架** | Ktor Client | 原生协程支持、WebSocket、内嵌 OkHttp 引擎 |
| **序列化** | kotlinx.serialization | Kotlin 原生序列化 |
| **协程** | kotlinx-coroutines | 结构化并发 |
| **时间** | kotlinx-datetime | Kotlin 原生时间 |
| **依赖方式** | 各平台独立模块 | 只依赖 gateway-core，不重复依赖 Ktor |

## 3. 核心接口

### 3.1 PlatformAdapter（平台适配器）

```kotlin
interface PlatformAdapter {
    val platformId: PlatformId
    val name: String
    val connectionState: ConnectionState

    suspend fun connect(): ConnectResult
    suspend fun disconnect()
    suspend fun sendMessage(message: OutgoingMessage): SendResult
    suspend fun sendTypingIndicator(chatId: String)
    suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult
    suspend fun deleteMessage(chatId: String, messageId: String): Boolean

    fun onMessageReceived(handler: (IncomingMessage) -> Unit)
    fun onConnectionStateChanged(handler: (ConnectionState) -> Unit)
    fun onError(handler: (PlatformError) -> Unit)
}
```

### 3.2 AgentRunner（AI 处理器 SPI）

```kotlin
interface AgentRunner {

    sealed class Result {
        data class Success(...) : Result()
        data class Interrupted(...) : Result()
        data class Failure(...) : Result()
        data class NeedMoreInput(...) : Result()
        object Silent : Result()
    }

    // 使用协程取消实现中断，不需要手动 flag
    suspend fun process(message: IncomingMessage, session: GatewaySession): Result
}
```

### 3.3 SessionManager（会话管理 SPI）

```kotlin
interface SessionManager {
    suspend fun getOrCreateSession(source: MessageSource): GatewaySession
    suspend fun getSession(sessionKey: String): GatewaySession?
    suspend fun updateSession(session: GatewaySession)
    suspend fun markProcessing(sessionKey: String)
    suspend fun markProcessingComplete(sessionKey: String)
    fun isProcessing(sessionKey: String): Boolean
    suspend fun updateSessionStats(...)
    fun observeSession(sessionKey: String): Flow<GatewaySession?>
}
```

### 3.4 HookPipeline（Hook 管道）

```kotlin
interface HookPipeline {
    enum class Event { BEFORE_RECEIVE, AFTER_RECEIVE, BEFORE_AGENT, ... }
    sealed class Result { Continue, Halt, ModifyMessage, ModifyResponse }
    interface Hook { ... }
}
```

## 4. 会话存储设计

### 4.1 目录结构

```
{baseDir}/
└── gateway/               # 固定子目录，避免与用户数据冲突
    └── sessions/         # 会话存储目录
        └── {sessionKey}/  # 每个会话一个目录
            └── session.jsonl  # JSONL 格式，每次更新追加一行
```

### 4.2 JSONL 格式

```jsonl
{"key":"feishu:chat123:user456","platform":"feishu","chatId":"chat123","userId":"user456",...}
{"key":"feishu:chat123:user456","platform":"feishu",...}  // 更新时追加新行
{"key":"feishu:chat123:user456","platform":"feishu",...}  // 再次更新
```

**读取时**：取最后一行作为最新状态

## 5. 飞书 WebSocket 实现

### 5.1 连接流程

```
1. POST /open-apis/auth/v3/tenant_access_token/internal
   → 获取 tenant_access_token

2. GET /open-apis/gateway/v1/connect
   → 获取 wss:// 开头的 WebSocket URL

3. 使用 WebSocket URL 建立连接
   → URL 中已包含临时凭证，无需额外握手
```

### 5.2 消息格式转换

```
飞书推送: { type: "event", event: { header: {...}, event: {...} } }
              ↓ 提取 header 和 inner event
Parser 期望: { header: {...}, event: {...} }
```

### 5.3 心跳机制

```
收到: { type: "ping" }
回复: { type: "pong" }
```

## 6. 消息处理流程

```
PlatformAdapter.onMessageReceived(message)
         │
         ▼
    HookPipeline.run(BEFORE_RECEIVE)
         │
         ▼
    ConcurrencyController.acquire()
         │
         ▼
    SessionManager.getOrCreateSession()
         │
         ▼
    HookPipeline.run(BEFORE_VALIDATE)
         │
         ▼
    AgentRunner.process()        ← 外部注入的 AI 处理
         │
         ▼
    DeliveryRouter.deliver()     ← 发送响应
         │
         ▼
    HookPipeline.run(AFTER_SEND)
         │
         ▼
    ConcurrencyController.release()
```

## 7. 使用示例

```kotlin
fun main() = runBlocking {
    val gateway = GatewayEngineBuilder()
        .withConfig(GatewayConfig(maxConcurrentSessions = 10))
        .withFileSessionStorage(File("./data"))  // → ./data/gateway/sessions/
        .withAgentRunner(MyAgentRunner())       // 自定义 AI 处理器
        .build()

    // 注册飞书适配器
    gateway.registerAdapter(
        FeishuAdapter(
            FeishuConfig(
                appId = "cli_xxx",
                appSecret = "xxx"
            )
        )
    )

    // 注册 Hook
    gateway.registerHook(LoggingHook())
    gateway.registerHook(WhitelistHook(allowedUsers = setOf("ou_xxx")))

    gateway.start()
}
```

## 8. 依赖关系

```
gateway-core/
    └── Ktor Client (core + okhttp + websockets)

platforms/feishu/   →  gateway-core (仅传递)
platforms/telegram/ →  gateway-core (仅传递)
platforms/weixin/    →  gateway-core (仅传递)
```

**优势**：
- 核心零平台依赖
- 各平台模块只依赖 core，不重复引入 Ktor
- 新增平台不影响其他模块
