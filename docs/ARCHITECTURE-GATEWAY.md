# IM 消息网关架构

## 1. 概述

Gateway 把"消息平台"和"Agent 推理"严格切成两层，新增平台只需写一个适配器。支持守护进程（JVM）和 Android 客户端两种入口形态。

## 2. 架构分层

```
                    ┌─────────────────────────────────┐
                    │       飞书 / Telegram / 微信     │  ← 平台侧
                    └────────────────┬────────────────┘
                                     │ WebSocket / 长轮询
                                     ▼
        ┌────────────────────────────────────────────────┐
        │       平台适配器 (gateway:platforms:*)          │  ← 翻译层
        │   平台事件  ──▶  IncomingMessage                │
        │   平台回包  ◀──  OutgoingMessage                │
        └────────────────┬───────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────────────────────────┐
        │             GatewayEngine                       │  ← 引擎
        │   ┌──────────────────────────────────────┐     │
        │   │ AgentRunner ──▶ 你的 Agent (agent { })│     │
        │   └──────────────────────────────────────┘     │
        │   HookPipeline / SessionManager / 配置          │
        └────────────────────────────────────────────────┘
```

## 3. 模块结构

```
gateway:core               ← 引擎核心：GatewayEngine、PlatformAdapter、AgentRunner、路由
gateway:platforms:feishu   ← 飞书适配器
gateway:platforms:telegram ← Telegram 适配器
gateway:platforms:weixin   ← 微信适配器
gateway:jvm                ← JVM 守护进程入口
gateway:app                ← Android 客户端入口
```

## 4. 核心组件

| 组件 | 接口 | 职责 |
|------|------|------|
| `GatewayEngine` | 顶层门面 | 管理适配器生命周期、消息路由、并发控制 |
| `PlatformAdapter` | 平台适配器 | 平台事件 ↔ 引擎消息的翻译 |
| `AgentRunner` | 消息处理接口 | 将消息委托给 Agent 推理 |
| `DeliveryRouter` | 消息路由 | 将回包路由到正确的平台适配器 |
| `ConcurrencyController` | 并发控制 | 限制最大并发会话数 |
| `HookPipeline` | 网关 Hook | 消息接收/发送/验证的生命周期钩子 |
| `GatewaySessionManager` | 会话管理 | Gateway 层的会话管理 |

### 4.1 GatewayEngine

```kotlin
public interface GatewayEngine {
    public suspend fun start()
    public suspend fun stop()
    public fun registerAdapter(adapter: PlatformAdapter)
    public fun setAgentRunner(runner: AgentRunner)
    public fun setHookPipeline(pipeline: HookPipeline)
    public fun observeState(): Flow<GatewayState>
    public fun observeErrors(): Flow<GatewayError>
}
```

**DefaultGatewayEngine** 是唯一实现，内部组件：
- `ConcurrentHashMap` 管理适配器
- `ConcurrentHashMap` 管理处理中的 Job
- `ConcurrentHashMap` 管理 Session Mutex
- `MutableStateFlow` 推送引擎状态
- `MutableSharedFlow` 推送错误事件

### 4.2 PlatformAdapter

```kotlin
public interface PlatformAdapter {
    val platformId: PlatformId
    suspend fun connect(): ConnectResult
    suspend fun disconnect()
    suspend fun sendMessage(message: OutgoingMessage): SendResult
    fun onMessageReceived(handler: (IncomingMessage) -> Unit)
    fun onConnectionStateChanged(handler: (ConnectionState) -> Unit)
    fun onError(handler: (PlatformError) -> Unit)
}
```

### 4.3 AgentRunner

```kotlin
public interface AgentRunner {
    suspend fun process(message: IncomingMessage, session: GatewaySession): Result

    sealed class Result {
        data class Success(val responseContent: MessageContent) : Result()
        data class Interrupted(val reason: String) : Result()
        data class Failure(val error: String, val exception: Throwable?) : Result()
        data class NeedMoreInput(val prompt: String, val timeoutSeconds: Int?) : Result()
        object Silent : Result()
    }
}
```

## 5. 消息处理流程

```
IncomingMessage 到达
  │
  ├─ HookPipeline.BEFORE_RECEIVE
  │     └─ Halt → 丢弃
  │
  ├─ 获取 Session (getOrCreate)
  ├─ 获取并发槽 (ConcurrencyController.acquire)
  │     └─ 失败 → 丢弃 + 错误事件
  ├─ 标记 Processing (sessionManager.markProcessing)
  │
  ├─ HookPipeline.AFTER_RECEIVE
  ├─ HookPipeline.BEFORE_VALIDATE → Halt → 返回 Failure
  ├─ HookPipeline.AFTER_VALIDATE
  │
  ├─ 发送 Typing Indicator (可选)
  │
  ├─ HookPipeline.BEFORE_AGENT
  ├─ AgentRunner.process(message, session)
  ├─ HookPipeline.AFTER_AGENT
  │
  ├─ 发送响应
  │     ├─ Success → DeliveryRouter.deliver
  │     ├─ NeedMoreInput → 发送提示
  │     ├─ Failure → 错误事件
  │     └─ Interrupted / Silent → 静默
  │
  ├─ HookPipeline.BEFORE_SEND / AFTER_SEND
  │
  ├─ 释放并发槽 (ConcurrencyController.release)
  ├─ 标记处理完成
  └─ 处理 Pending 队列
```

## 6. 并发模型

- 每个 Session 一个 `Mutex`，确保同一会话的消息**串行处理**
- `ConcurrencyController` 限制全局最大并发会话数（`maxConcurrentSessions`）
- 处理中的会话收到新消息时，消息进入 pending 队列，旧 job 被取消
- 使用 `ConcurrentHashMap` 管理适配器和处理中的 job

## 7. 网关 Hook

Gateway 的 Hook 事件与 Agent 的 Hook 事件是独立的，覆盖消息生命周期：

```
HookPipeline.Event
├── ON_START / ON_STOP              — 引擎启动/停止
├── ON_PLATFORM_CONNECT             — 平台连接
├── BEFORE_RECEIVE / AFTER_RECEIVE  — 消息接收
├── BEFORE_VALIDATE / AFTER_VALIDATE — 消息验证
├── BEFORE_AGENT / AFTER_AGENT      — Agent 推理
├── BEFORE_SEND / AFTER_SEND        — 消息发送
├── ON_SEND_FAILED                  — 发送失败
└── ON_ERROR                        — 错误
```

## 8. 消息重试

`GatewayConfig.messageRetryCount` 控制发送失败时的重试次数，`messageRetryDelayMs` 控制重试间隔。重试在 `DeliveryRouter.deliver` 返回 `Failure(retryable=true)` 时触发。

## 9. 网关入口

**gateway:jvm** — 守护进程形态：
- `./gradlew :gateway:jvm:run` 启动
- `application.properties` + env 覆盖配置
- 加载 Core + 平台适配器 + Agent 工厂

**gateway:app** — Android 客户端形态：
- 让 Android 设备以客户端身份连接到 Gateway
- 可作为轻量远程 Agent 入口