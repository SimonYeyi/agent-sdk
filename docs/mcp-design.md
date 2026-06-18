# MCP 模块设计文档

> 对应源码:`mcp/`(本仓库 `mcp/src/main/kotlin/io/github/yeyi/agent/mcp/`)
> 协议基线:Model Context Protocol 2025-06-18
> 编写日期:2026-06-18
> 状态:审计完成,修复进行中

---

## 1. 模块定位

`mcp` 是 Agent SDK 的 **MCP 客户端模块**,把外部 [Model Context Protocol](https://modelcontextprotocol.io) 服务器包装成 LLM 可发现的 `Tool`,使 Agent 能调用任意符合 MCP 规范的第三方工具服务。

| 维度 | 现状 |
|---|---|
| 协议基线 | MCP 2025-06-18 |
| 支持传输 | Stdio(子进程) + HTTP+SSE(legacy) |
| 暴露给 LLM 的工具 | `load_mcp_tools` (发现工具) + `call_mcp_tool` (调用工具) |
| 注册方式 | `AgentBuilder.mcp(registry)` |
| 多 server 支持 | `McpServerRegistry` |

**与其他模块的关系**:
- 仅依赖 `agent` 核心模块的 `Tool` / `ToolContext` / `ToolExecutionResult` / `ToolParameters` 接口
- 不依赖 `skill` / `session` / `providers` / `app`
- 设计原则:把 MCP 协议细节封装在模块内,对上层只暴露「load / call」两个 Tool

---

## 2. 架构图

```
                ┌────────────────────────────────────────┐
                │       AgentBuilder.mcp(registry)       │
                │     注册 LoadMcpTool + CallMcpTool     │
                └─────────────┬──────────────────────────┘
                              │
                ┌─────────────▼──────────────────────────┐
                │        McpServerRegistry               │
                │   维护 name → McpServer 的映射         │
                │   提供 listTools / callTool / closeAll │
                └─────────────┬──────────────────────────┘
                              │
                ┌─────────────▼──────────────────────────┐
                │        McpServer (interface)           │
                │   name / description / transport       │
                │   listTools / callTool / close         │
                └─────────────┬──────────────────────────┘
                              │
                ┌─────────────▼──────────────────────────┐
                │      GenericMcpServer                  │
                │  - initialize 握手(A1)                │
                │  - tools/list 分页缓存(A6/D1/A5)      │
                │  - 错误处理(error → 异常)              │
                │  - ping(A3)                            │
                └─────────────┬──────────────────────────┘
                              │
                ┌─────────────▼──────────────────────────┐
                │      McpTransport (sealed)             │
                │   ┌──────────────┐ ┌────────────────┐ │
                │   │StdioTransport│ │SseTransport    │ │
                │   │  - 子进程     │ │ - Streamable   │ │
                │   │  - 行分隔     │ │   HTTP(A2)     │ │
                │   │  - drain     │ │ - SSE/JSON     │ │
                │   │    stderr    │ │   分流(C7/C8)  │ │
                │   │  - 超时/取消 │ │ - session id   │ │
                │   │  - 三段关闭  │ │ - 超时         │ │
                │   └──────────────┘ └────────────────┘ │
                └────────────────────────────────────────┘
```

---

## 3. 协议合规性审计(2025-06-18 对照)

按问题严重度分级:🔴 必修(协议违反)/ 🟠 高优(显著影响)/ 🟡 中优(实现不完整)/ 🟢 低优(代码质量)

### 3.1 协议层(🔴 A 类)

| ID | 严重度 | 问题 | 当前表现 |
|---|---|---|---|
| **A1** | 🔴 | 完全缺失 `initialize` 握手 | 直接调 `tools/list`/`tools/call`,任何规范 Server 会拒绝 |
| **A2** | 🔴 | SseTransport 是 2024-11-05 旧版 HTTP+SSE | 缺 `MCP-Protocol-Version`/`Mcp-Session-Id` 头,不支持 Streamable HTTP |
| **A3** | 🟠 | 缺 `ping` | 无健康检查手段 |
| **A4** | 🟠 | 缺 `notifications/cancelled` | 长任务无法取消,readLine 永久阻塞 |
| **A5** | 🟠 | 缺 `notifications/tools/list_changed` 处理 | 缓存永久不更新 |
| **A6** | 🟠 | 缺 `tools/list` 分页(`cursor`) | 工具多时静默丢失 |
| **A7** | 🟠 | 缺 `result.isError` 透传 | 工具层错误信号丢失,Agent 调度层看到的是"成功" |
| **A8** | 🟢 | 缺 `content` 多类型处理 | 已被"整体透传 result"方案覆盖,LLM 自决 |

### 3.2 JSON-RPC 层(🟠 B 类)

| ID | 严重度 | 问题 | 处理决定 |
|---|---|---|---|
| B1 | — | result/error 互斥未强制 | **撤掉**:`error != null` 已抛异常;两者都 null 时静默返回 `{}` 作为「容错兜底」,符合"minimal defensive coding" |
| B2 | — | error 字段无类型安全 | **撤掉**:`JsonElement` 透传,LLM 直接读 |
| B3 | 🟡 | JSON 手写字符串模板拼接 | 改用 `kotlinx.serialization` 序列化 |

### 3.3 Transport 层(🟠 C 类)

| ID | 严重度 | 问题 |
|---|---|---|
| **C1** | 🔴 | StdioTransport.stderr 永不读取,可能填满阻塞 |
| **C2** | 🔴 | StdioTransport.readLine 永久阻塞,父协程 cancel 无法中断 |
| **C3** | 🟠 | 双锁 + 双重 `nextId`(`StdioTransport` 和 `GenericMcpServer` 各一个) |
| **C4** | 🟠 | `ensureStarted` 非线程安全,并发首次调用会重复启动 |
| **C5** | 🟠 | `close()` 一次性 destroyForcibly,不符合三段式关闭 |
| **C7** | 🟠 | SSE 解析不完整(无多行 `data:`、注释、id/event/retry) |
| **C8** | 🟠 | 不处理 `application/json` 直接响应 |
| **C9** | 🟡 | SseTransport 无超时 |
| **C10** | 🟡 | 每个 SseTransport 自建 HttpClient |
| **C11** | 🟡 | SseTransport 不处理 auth/redirect |

### 3.4 Server/Registry/Tool 集成层(🟠 D 类)

| ID | 严重度 | 问题 | 处理决定 |
|---|---|---|---|
| D1 | 🟠 | `toolsCache` 永久缓存,无失效机制 | 由 A5 `listChanged` 修复 |
| D2 | — | 错误处理过宽 | **撤掉**:`MCPServerException` 透传 JsonElement,LLM 自行解读 |
| D3 | 🟠 | CallMcpTool 不透传 `isError` | 见 A7,读一个 boolean 字段 |
| D4 | — | 错误码丢失 | **撤掉**:同 B2 理由 |
| **D5** | 🟡 | `LoadMcpTool.buildDescription` 立即求值 | 改 lazy |
| **D6** | 🟡 | `LoadMcpTool` 硬拼接 "已激活" 前缀,误导 LLM | 改为直接透传 |
| D7 | — | `server_name` schema 缺 enum | **撤掉**:description 已列出,加 enum 增加维护成本 |
| D8 | 🟢 | `McpServerRegistry` 缺 unregister | 暂缓 |
| D9 | 🟢 | `closeAll` 串行不等待 | 暂缓 |
| D10 | 🟢 | `McpExtensions.mcp()` 工具名硬编码 | 暂缓 |

### 3.5 关键设计决策(本次确认)

1. **只透传 `result.content` 给 LLM**:CallMcpTool 从 MCP `tools/call` 响应中提取 `content` 数组(协议规定的标准输出通道),作为 `ToolExecutionResult.content` 喂给 LLM;content 字段缺失或为 null 时 fallback 到空字符串(避免 LLM 看到 `"null"` 字符串)。`isError`、`structuredContent` 等其他元数据对 LLM 隐藏,只在 SDK 内部按需消费。LoadMcpTool 仍透传完整的 `tools/list` 响应,因为 `content` 数组不是该场景的语义中心。
2. **错误信号双层映射**:JSON-RPC 协议层错误(`response.error`)→ 抛 `MCPServerException` → ToolRegistry 转 `isError=true`;MCP 应用层错误(`result.isError=true`)→ CallMcpTool 读出 → 设 `ToolExecutionResult.isError=true`(content 仍是 content 数组,SDK 内部读 isError 但不传给 LLM)。两层各自走自己的路径。
3. **load_mcp_tools 取消硬拼接**:MCP 协议里 `tools/list` 不是"激活"操作,SDK 不应硬编码语义标签。

---

## 4. 修复计划

### 4.1 优先级总览

| 优先级 | 数量 | 范围 |
|---|---|---|
| **P0 必修** | 4 项 | A1, A2, C1, C2 |
| **P1 高优** | 6 项 | A4, A6, A7/D3, C5, C7+C8, D1+A5 |
| **P2 中优** | 5 项 | C3, C4, A3, C9/C10, D5/D6 |
| **P3 低优** | 2 项 | B3, 单测 |
| **撤回** | 6 项 | B1, B2, D2, D4, D7, A8 |

### 4.2 P0:必修(协议正确性 + 资源安全)

#### A1 — `initialize` 握手
**目标**:GenericMcpServer 在首次 `listTools` 之前自动完成 `initialize` → 响应 → `notifications/initialized` 三步握手。

**实现要点**:
- 在 GenericMcpServer 内部加 `initializeMutex`,包裹首次 `listTools()` 调用
- 发送 `initialize` 请求,带 `protocolVersion: "2025-06-18"`、`capabilities: {}`、`clientInfo: { name: "agent-sdk", version: "..." }`
- 检查响应 `protocolVersion`,如果不匹配 → 抛 `McpProtocolException`(包含 supported/requested version)
- 缓存 `serverInfo` / `protocolVersion` / `capabilities` 到 GenericMcpServer 实例字段
- 发送 `notifications/initialized` 通知(无 id,fire-and-forget)

#### A2 — SseTransport 重写为 Streamable HTTP
**目标**:支持 MCP 2025-06-18 的 Streamable HTTP 传输。

**实现要点**:
- POST 到单一 MCP endpoint,带 `Accept: application/json, text/event-stream`
- 按响应 `Content-Type` 分流:
  - `application/json` → 直接解析单 JSON 响应
  - `text/event-stream` → 解析 SSE 流
- 所有请求带 `MCP-Protocol-Version: 2025-06-18` 头
- 服务器若返回 `Mcp-Session-Id` 头,后续请求透传
- 接受客户端对 404 session expired 的处理(本 SDK 不自动重试,抛异常让上层决策)

#### C1 — drain stderr
**目标**:防止子进程 stderr 管道填满导致阻塞。

**实现要点**:
- StdioTransport 启动时开启一个独立 `CoroutineScope`,在 `Dispatchers.IO` 上循环 `stderr.readLine()`
- 通过 `McpLogger` 接口转发(SLF4J 或 JDK Logger),无 logger 时静默丢弃
- close() 时取消该 scope

#### C2 — 超时 + 取消
**目标**:防止 readLine 永久阻塞,父协程 cancel 时及时中断。

**实现要点**:
- `send` 用 `withTimeout` 包裹(默认 30s,可在 transport 构造时配置)
- 把 `BufferedReader.readLine()` 替换为可取消实现:每次 `read` 前 `ensureActive()`,挂起用 channel
- 父协程 cancel 时,read 协程立即抛 `CancellationException`,清理 process 资源

### 4.3 P1:高优(可用性 + 健壮性)

#### A4 — `notifications/cancelled`
**目标**:父协程 cancel 时通知 server 停止处理。

**实现要点**:
- StdioTransport.send 启动前用 `coroutineContext[Job]` 关联父 job
- 用 `invokeOnCompletion` 监听 cancel(注意区分正常完成)
- 取消时往 stdin 写 `notifications/cancelled` 通知(method = "notifications/cancelled", params = { requestId, reason })
- 仅对带 id 的请求发送,通知本身无 id

#### A6 — `tools/list` 分页
**目标**:支持 cursor 翻页拿全所有工具。

**实现要点**:
- `McpServer.listTools(cursor: String? = null): JsonElement` 接受可选 cursor
- `McpServerRegistry.listTools(serverName, cursor)` 透传
- `LoadMcpTool` 不需要改 schema(只传 server_name),分页在 GenericMcpServer 内部完成:循环直到 `nextCursor` 为空
- 缓存逻辑调整为缓存完整列表(不再按 cursor 分页缓存)

#### A7/D3 — `isError` 透传
**目标**:把 MCP 应用层错误信号映射到 SDK 契约字段。

**实现要点**:CallMcpTool.execute 提取 `result.content` 数组作为 SDK `ToolExecutionResult.content`,同时读取 `result.isError` 映射到 `isError`:
```kotlin
val isError = result.jsonObject["isError"]
    .let { (it as? JsonPrimitive)?.booleanOrNull }
    ?: false
val content = result.jsonObject["content"]?.toString() ?: ""
return ToolExecutionResult(content = content, isError = isError)
```

**说明**:
- `content` 缺失或为 null 时 fallback 到空字符串(避免 LLM 看到 `"null"` 字面量)
- `isError` 字段非 boolean 时(`null` / 字符串 / 对象)安全降级为 `false`
- `content` 数组按 MCP 协议是 `tools/call` 的标准输出通道,LLM 直接读;`isError` 等元数据对 LLM 隐藏,只在 SDK 内部消费

#### C5 — 三段式关闭
**目标**:符合 MCP stdio shutdown 规范。

**实现要点**:
```kotlin
override suspend fun close() {
    withContext(Dispatchers.IO) {
        runCatching { stdin?.close() }           // 1) close stdin
        runCatching { process?.waitFor(5, TimeUnit.SECONDS) }
            ?: runCatching { process?.destroy() } // 2) SIGTERM
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
            ?: runCatching { process?.destroyForcibly() } // 3) SIGKILL
        runCatching { stdout?.close() }
        runCatching { stderr?.close() }
    }
}
```

#### C7 + C8 — 完整 SSE 解析 + Content-Type 分流
**目标**:SSE 解析符合 W3C SSE 规范,支持多行 `data:`、注释、id/event/retry 字段;响应 Content-Type 分流。

**实现要点**:
- 按 `Content-Type` 头分两路:
  - `application/json` → `decodeFromString<JsonRpcResponse>(body)`
  - `text/event-stream` → 调 SSE 解析器
- SSE 解析器:按 `\n\n` 切分 event,每个 event 内:
  - `:` 开头为注释,跳过
  - `data:` 累加到 list(用 `\n` 拼接多行),空行 + 遇到非 data 字段 flush
  - `id:` / `event:` / `retry:` 分别记录
- 找 id 匹配的 response 后 break(允许在响应前有 progress notification)

#### D1 + A5 — listChanged 失效缓存
**目标**:GenericMcpServer 监听 listChanged 通知,失效 toolsCache。

**实现要点**:
- StdioTransport 后台读 stdout 时识别 `notifications/tools/list_changed`(无 id,JSON-RPC 通知)
- 通过 transport 暴露的 `Flow<JsonElement>` 把通知广播给 GenericMcpServer
- GenericMcpServer 收到时清空 `toolsCache`,下次 `listTools()` 重新拉

### 4.4 P2:中优(健壮性 + 可维护性)

#### C3 — 消除双重 nextId
**目标**:id 只在 transport 层分配,server 层透传。

**实现要点**:`McpTransport.send(request: JsonRpcRequest)` 透传 request.id,server 层构造 request 时把 `nextId` 从 transport 借过来用一次。**实际上更简单**:让 transport.send 内部分配 id,把 request.id 改为可选(`Int?`),server 层不再分配。

#### C4 — ensureStarted 线程安全
**目标**:用 Mutex 保护 process 启动。

**实现要点**:把 `ensureStarted` 内的 ProcessBuilder.start() 用 startMutex 保护,多协程并发首次调用只启动一次。

#### A3 — ping
**目标**:McpServer 接口加 `suspend fun ping(): Boolean`,GenericMcpServer 透传,StdioTransport/SseTransport 各自实现。

#### C9/C10 — SseTransport 超时 + 共享 client
**目标**:HttpClient 共享,加超时配置。

**实现要点**:McpTransport 工厂 + 静态 `HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }`,所有 SseTransport 实例引用同一 client。

#### D5 — buildDescription lazy
**目标**:`LoadMcpTool.description` 改 `by lazy`,后注册到 registry 的 server 也能反映。

#### D6 — LoadMcpTool 去掉硬拼接
**目标**:`"$serverName MCP Server 已激活,可用工具如下:\n$result"` → `result.toString()`。

### 4.5 P3:低优(代码质量)

#### B3 — 改用 kotlinx.serialization
**目标**:用 `@Serializable JsonRpcRequest` 直接 `Json.encodeToString(request)`,消除字符串模板注入风险。

#### 单测
最低覆盖:
- `JsonRpcRequest` 序列化往返
- `StdioTransport` 进程启动/读/写/超时/cancel/drain
- `SseTransport` 单 JSON 响应 / SSE 多 data / 注释行
- `GenericMcpServer` 错误处理 / isError 透传
- `CallMcpTool` 透传逻辑
- `LoadMcpTool` 透传逻辑

### 4.6 撤回项说明

| ID | 撤回理由 |
|---|---|
| B1 | 用户确认「error == null && result == null」是容错兜底,不是 bug |
| B2 | JsonElement 透传即可,LLM 直接读 JSON |
| D2 | 异常体系已经够用,不强求结构化 |
| D4 | 错误码在 JSON 里,LLM 自取 |
| D7 | description 已含 server 列表,加 enum 重复 |
| A8 | 整体透传 result 后,LLM 自己处理多类型 content |

---

## 5. 设计原则(供后续维护参考)

1. **透传优先**:除非协议明确规定 SDK 必须做字段映射(如 `isError`),否则整体透传 JSON 给 LLM。
2. **协议字段最小读取**:SDK 只读 MCP 规范规定的状态字段(`isError`、`nextCursor` 等),不解析 `content` 内部结构。
3. **错误三层模型**:
   - 协议层错误(JSON-RPC error)→ 抛异常 → ToolRegistry 转 isError
   - 应用层错误(MCP result.isError)→ 字段映射 → ToolExecutionResult.isError
   - 工具业务错误(content 数组里某项是 error)→ LLM 自决
4. **资源安全**:所有 transport 都要 drain stderr、加超时、协程可取消、关闭三段式。
5. **缓存一致性**:缓存必须有失效机制,监听 listChanged 通知。

---

## 6. 待办(按 commit 顺序)

1. `refactor(mcp): 透传 result.isError,去除 LoadMcpTool 硬拼接` — A7/D3, D6(纯几行)
2. `fix(mcp): StdioTransport drain stderr、加超时、协程可取消、三段式关闭` — C1, C2, C5
3. `refactor(mcp): 消除双重 nextId 和 ensureStarted 线程安全` — C3, C4
4. `feat(mcp): 实现 MCP initialize 握手` — A1
5. `feat(mcp): 实现 listChanged 通知和 cancelled 通知` — D1+A5, A4
6. `feat(mcp): 实现 ping 和 tools/list 分页` — A3, A6
7. `feat(mcp): 重写 SseTransport 为 Streamable HTTP` — A2, C7+C8, C9/C10
8. `refactor(mcp): buildDescription 改 lazy,JsonRpcRequest 改用 kotlinx.serialization` — D5, B3
9. `test(mcp): 添加单测覆盖关键路径`
