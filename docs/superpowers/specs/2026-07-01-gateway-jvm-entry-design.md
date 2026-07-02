# Gateway JVM 入口设计

> 日期:2026-07-01 · 状态:Draft(待用户审阅)
> 模块:`gateway/jvm`(新建)· 仅 feishu · 镜像 `:gateway:app` 的装配链路
> 范围:提供一个用 Kotlin 写、跑在 JVM 上的可独立运行 daemon,作为 `:gateway:app`(Android application)的非 Android 对等入口;Android 侧不修改。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | gateway-jvm-entry |
| 关联模块 | `gateway/jvm`(新)、`:gateway:core`、`:gateway:platforms:feishu`、`:session`、`:agent`、`:hook`、`:providers:anthropic` |
| 关联前置 | `:gateway:app` 已存在并稳定运行(参见 `2026-06-25-gateway-app-session-integration-design.md`)、`GatewayEngineBuilder` / `DefaultAgentRunner` / `FeishuAdapter` 的对外 API |
| 不在范围 | Android `:gateway:app` 的任何修改;多平台 daemon(telegram/weixin 暂不接入);统一的装配层抽象(本次不抽公共模块,Android 与 JVM 各保留一份);observability/metrics/健康检查端点;SDK 的对外发布形态(Maven 坐标等) |
| 破坏性变更 | 否 |

---

## 1. 动机

`:gateway:app` 是**Android application 模块**:它的 `GatewayService.onCreate` 把 AnthropicProvider、`:session.SessionManager`、`FeishuAdapter`、`DefaultAgentRunner`、`GatewayEngineBuilder` 这条装配链跑起来,作为可独立安装的 Android 机器人应用。但这套装配完全嵌死在 Android 的 `Service` 生命周期(`startForegroundService` + `START_STICKY` + `onDestroy`)里,且依赖 `Context.filesDir`、`BuildConfig`、`Intent` 这些 Android 专属 API;在桌面 / 容器 / 自建服务器上跑不了。

**目标**:让同一套装配链路也能在一个普通 JVM 进程里跑起来 —— 用户 `java -jar` 一个 fat jar,就得到一个长驻的 feishu bot,不依赖 Android runtime、不依赖任何移动端框架。本质是把 `GatewayService` 里那 ~30 行"装配 + 启动 engine"的代码搬到一个没有 Android 依赖的环境里,逻辑保持一致。

**约束**(由 user 在 brainstorming 中确定):
- Kotlin(继续使用项目既有语言),目标是**平台层面**的 "纯 Java"(JVM-Only)而不是语言层面的 Java
- 跟 `:gateway:app` **结构上对称**(命名空间、文件粒度对应),但**Android 侧不抽公共模块**、不重构
- 仅 feishu 适配器
- 配置:`application.properties` + 系统环境变量覆盖;不带框架(Spring/Quarkus 等)
- 分发:Gradle `application` 插件 + Shadow 插件打 fat jar

---

## 2. 设计原则

- **镜像 Android 侧的装配**,但**不抽公共模块**:daemon 内部代码与 `GatewayService.startEngine` 1:1 对应(同一个 `AnthropicProvider`、同一个 `SessionManager(filesDir, HookPipeline())`、同一个 `DefaultAgentRunner` + lambda、同一份 `FeishuConfig` 字段);从 Android 抽公共 module 是后续独立提案,本次不预先抽象。
- **依赖全部走现有 SDK 模块,不顺手加新依赖**:`:gateway:jvm` 只 `implementation` 现成的 `:gateway:core`、`:gateway:platforms:feishu`、`:providers:anthropic`、`:session`、`:agent`、`:hook`;不引入新的日志库 / 配置库(配置用 JDK 自带 `java.util.Properties`)。
- **生命周期用 JVM 原语**:`main` + `addShutdownHook` 替代 Android 的 `Service` 生命周期;不在 JVM 上模拟 `START_STICKY`,daemon 一旦 stop 就退。
- **缺配置 fail-fast**:启动时必须键缺一就抛 `IllegalStateException` 并打印具体缺失项 + 取值来源提示,不静默用空串继续(避免"忘记设 key → 静默失败"的踩坑模式)。
- **统一扁平包名**:本模块包名 `io.github.yeyi.agent.gateway.jvm`,与 Android 侧 `io.github.yeyi.agent.gateway.app` 对称,方便后续对齐与按模块检索。
- **测试最小化,符合 `:gateway:app` 现有惯例**:Android `:gateway:app` 现有无装配链路单元测试(仅手动跑设备验证);本次也**不引入单元测试**。`application.properties`(不含真实 key)做默认 demo。

---

## 3. 架构与文件布局

### 3.1 模块布局

```
gateway/jvm/                                       ← 新模块
├── build.gradle.kts                              ← kotlin.jvm + application + shadow
├── src/main/kotlin/io/github/yeyi/agent/gateway/jvm/
│   ├── Main.kt                                   ← main()
│   ├── GatewayDaemon.kt                          ← 类比 GatewayService 的纯 JVM 形态
│   ├── GatewayDaemonConfig.kt                    ← application.properties + env 加载
│   └── DefaultAgentRunner.kt                     ← :gateway:app 那个 Runner 的副本
└── src/main/resources/
    └── application.properties.example            ← 示例配置(运行时可被外部覆盖)
```

`settings.gradle.kts` 增 `include(":gateway:jvm")`。
`build.gradle.kts` 新插件:`org.jetbrains.kotlin.jvm`、`application`、`com.gradleup.shadow`。

### 3.2 文件职责

| 文件 | 职责 | 与 Android 对应 |
|---|---|---|
| `Main.kt` | 主入口,创建 `GatewayDaemonConfig`、`GatewayDaemon`,启动并挂 shutdown hook | `:gateway:app/MainActivity.onCreate` 中转 `startGatewayService()` 的语义 |
| `GatewayDaemon.kt` | 持有 `scope` + `GatewayEngine`,封装 `start()` / `stop()`,内部 `launch { engine.start() }` | `:gateway:app/GatewayService.onCreate` / `onDestroy` |
| `GatewayDaemonConfig.kt` | 从 `application.properties`(env `GATEWAY_CONFIG` 可覆盖路径)解析 + 系统环境变量覆盖;校验必填字段 | 替代 `:gateway:app/BuildConfig.*` |
| `DefaultAgentRunner.kt` | 与 Android `:gateway:app/DefaultAgentRunner` 行为完全一致(类体复制) | :gateway:app/DefaultAgentRunner.kt |

### 3.3 数据流

```
Main.main()
  │
  ├── 1. 加载 config: GatewayDaemonConfig.load()
  │        ├── 读 properties 文件(env GATEWAY_CONFIG 或 ./application.properties)
  │        ├── 系统环境变量覆盖(key 形式: anthropic.api.key ↔ ANTHROPIC_API_KEY)
  │        └── 校验必填 → IllegalStateException if missing
  │
  ├── 2. 实例化 GatewayDaemon(config),创建 SupervisorJob+Dispatchers.IO scope
  │
  ├── 3. Runtime.getRuntime().addShutdownHook { daemon.stop() }
  │
  └── 4. daemon.start()
         │
         ├── AnthropicProvider(apiKey, baseUrl, model)
         │       ← config 字段
         │
         ├── SessionManager(baseDir, HookPipeline())
         │       ← baseDir = File(config.sessionStorageDir)
         │
         ├── GatewayEngineBuilder()
         │     .withFileSessionStorage(baseDir)
         │     .withAgentRunner(DefaultAgentRunner { accountId, sessionId, sessionName ->
         │         agent { memory(sessionManager.getOrCreate(...).memory); llmProvider(provider) }
         │     })
         │     .build()
         │
         ├── FeishuAdapter(FeishuConfig(appId, appSecret), scope)
         │
         ├── engine.registerAdapter(feishuAdapter)
         └── engine.start()  ← suspend,直到 stop()
```

---

## 4. 装配对齐(以 `:gateway:app/GatewayService.startEngine` 为基准逐项映射)

| Android 源 | Android 写法 | JVM daemon 写法 | 差异 |
|---|---|---|---|
| `BuildConfig.ANTHROPIC_API_KEY` 等 | `BuildConfig` 常量 | `config.anthropicApiKey` | 来源替换 |
| `BuildConfig.FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 同上 | `config.feishuAppId` / `config.feishuAppSecret` | 同上 |
| `filesDir` | Android `Context.filesDir` | `File(config.sessionStorageDir)`(`./data/gateway/sessions` 默认) | 路径来源 |
| `HookPipeline()` | 框架构造 | 同 | 无 |
| `serviceScope`(CoroutineScope) | Service 内持有的 scope | daemon 持有的 `private val scope` | 无 |
| `engine.start()` | `launch { startEngine() }` 异步;`STOP_STICKY` 由 Service 提供 | `daemon.start()` 在 `runBlocking` 内同步调;`addShutdownHook` 触发 `stop()` | 生命周期基座不同 |
| `DefaultAgentRunner(createAgent)` | `Lambda` 闭包 `sessionManager` 和 `llmProvider` | 同(逻辑完全一致) | 无 |

`accountId / sessionId / sessionName` 的派生在 `DefaultAgentRunner.process` 内逐字保留:
- `accountId = "gateway:${session.platform.value}"`
- `sessionId = "${session.chatId}:${session.userId}"`
- `sessionName = (message.content as? MessageContent.Text)?.text ?: sessionId`

这一份语义沉淀自 `2026-06-25-gateway-app-session-integration-design.md`,**不允许在 JVM 侧悄悄改**(会出现"两个 daemon 同一 session key 表现不一致"的隐式分裂)。

---

## 5. 配置

### 5.1 文件

`application.properties`(示例,真运行不强制):

```
# Required
anthropic.api.key=
anthropic.base.url=https://api.anthropic.com
anthropic.model=claude-sonnet-4-6
feishu.app.id=
feishu.app.secret=

# Optional
session.storage.dir=./data/gateway/sessions
gateway.max.concurrent.sessions=10
anthropic.api.timeout.seconds=120
```

### 5.2 加载顺序

1. **路径解析**:`config.path` 优先取 `System.getenv("GATEWAY_CONFIG")`;若未设置,fallback 到 `<cwd>/application.properties`。
   - 当路径由 `GATEWAY_CONFIG` 显式指定但文件**不存在** → `IllegalStateException`(避免拼错路径被静默忽略)
   - 当走 fallback 默认路径且文件**不存在** → 不报错,视为空(由环境变量/默认值补齐)
2. **属性加载**:`java.util.Properties` 读入文件。
3. **环境变量覆盖**:对每个键 `k`(全小写、`.` 分隔),若 `k.toUpperCase().replace('.', '_')` 在环境中存在且非空,替换值。
4. **可选键默认值**:补齐 `session.storage.dir` 等。
5. **必填校验**:`anthropic.api.key` / `anthropic.base.url` / `anthropic.model` / `feishu.app.id` / `feishu.app.secret` 五项中任一为空 → `IllegalStateException`,message 列出全部缺失项 + 提示 `GATEWAY_CONFIG` 路径;

### 5.3 解析输出

`data class GatewayDaemonConfig(val anthropicApiKey: String, val anthropicBaseUrl: String, val anthropicModel: String, val feishuAppId: String, val feishuAppSecret: String, val sessionStorageDir: String, val maxConcurrentSessions: Int, val anthropicApiTimeoutSeconds: Int)`

字段命名与现有 SDK 风格(数据类 + `val` 字段)一致,可通过 `copy()` 派生测试 fixture。

---

## 6. 生命周期与停止

- `Main.main()`:
  ```kotlin
  fun main() {
      val config = GatewayDaemonConfig.load()
      val daemon = GatewayDaemon(config)
      Runtime.getRuntime().addShutdownHook(Thread { daemon.stop() })
      daemon.start()
  }
  ```
- `GatewayDaemon.start()`:
  - 内部直接 `runBlocking { engine.start() }`(engine.start 是 `suspend fun`,由它挂起即代表 daemon 进入活跃态)
  - 同步结构,顶层 `start()` 阻塞 main 线程直至 engine 停止
- `GatewayDaemon.stop()`:
  - `scope.launch { engine.stop() }` + `scope.cancel()`
  - 关掉后 main 线程返回,JVM 自然 exit
- **shutdown hook 序列保证**: JVM 触发钩子时所有钩子并行执行;本 daemon 内部 stop 是幂等的(重复调 `engine.stop()` 不会抛);`scope.cancel()` 在 `engine.stop()` 之后调用无副作用。

---

## 7. 分发与构建

### 7.1 `build.gradle.kts` 摘要

- 插件:`org.jetbrains.kotlin.jvm` + `application` + `com.gradleup.shadow`
- `application { mainClass.set("io.github.yeyi.agent.gateway.jvm.MainKt") }`
- `tasks.jar` / `shadowJar`:输出 `gateway-jvm-0.1.0(-all).jar`
- 依赖(全部 `implementation`,纯 jvm):`:gateway:core`、`:gateway:platforms:feishu`、`:providers:anthropic`、`:session`、`:agent`、`:hook`,加上 `kotlinx-coroutines-core`(其余 ktor/engine 通过 `:providers:anthropic` 的 `api` 依赖传递进来:`ktor-client-core` + `ktor-client-cio`,无需冗余声明)。feishu 适配器自身不直接用 ktor(走 OAPI SDK + `Client.Builder(...).start()`),所以**不引入** `ktor-client-okhttp`(`gateway/app` 那侧是 Android 链路专用)。
- `tasks.test { useJUnit() }`(占位,不写测试;但保持与既有 jvm 模块一致便于后续)
- Java/Kotlin toolchain 21(与 `gateway/core/build.gradle.kts` 一致)

### 7.2 运行方式

- 本地开发:`./gradlew :gateway:jvm:run`(或 `gradle :gateway:jvm:run`)
- 生产部署:`java -jar gateway-jvm-0.1.0-all.jar`

---

## 8. 测试

按现有惯例——`:gateway:app` 没有装配链单元测试,JVM daemon 本次也不引入。验证方式:`gradle :gateway:jvm:run` 后手动发给 feishu bot 一条消息,确认回复正常。

> 注:本设计不强制加 `GatewayDaemonConfigTest` 等单元测试。若未来要在 CI 防回归,再单独开提案。

---

## 9. 风险与后续

| 风险 | 缓解 |
|---|---|
| 两份装配代码(Android Service + JVM Main)在迭代中漂移 | 短期接受;长期建议开"抽公共 assembler"提案,**本设计不留接口预留位** |
| 缺配置时 daemon 静默启动 | startup fail-fast 校验 + 错误信息包含键名 + 提示环境变量名 |
| Shadow fat jar 漏包(feishu 依赖 oapi-sdk 有 native 二进制) | 由 Shadow 的 `append 'META-INF'` + Android `oapi-sdk` 仅在 Android 侧 jar 引入 native;jvm 模块**不引入 oapi-sdk**(feishu 适配器 Ktor 实现不需要),验证构建产物 |
| `addShutdownHook` 时 engine 已异常退出导致 stop 抛 | `stop()` 内部 catch + 幂等 |

---

## 10. 验收标准

- [ ] `gradle :gateway:jvm:assemble` 通过,生成 fat jar
- [ ] `gradle :gateway:jvm:run` 可启动 daemon 并打印"feishu connected"类似的 ready 状态
- [ ] 配置缺失时启动失败并打印清晰 message
- [ ] SIGTERM 触发后 30s 内 graceful shutdown
- [ ] 发给 bot 一条消息,bot 给出符合 `:session` memory 的上下文回复(对比 `:gateway:app` 行为一致)
- [ ] `:gateway:app` 在本次改动后仍可独立编译与运行
