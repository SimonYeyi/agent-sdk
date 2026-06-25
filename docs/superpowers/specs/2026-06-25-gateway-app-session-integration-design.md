# :gateway:app 与 :session 模块的整合设计

> 日期:2026-06-25 · 状态:**Design Ready**(待实现)
> 模块:`app`(新,Android application,放在 `gateway/app/`,含 `MainActivity` + `GatewayService` + `DefaultAgentRunner`)/ `session`(`create` 加可选 `sessionId` 参数);`gateway-core` 不动
> 范围:让 gateway 框架通过 `:session` 持久化对话历史(供 agent loop 作 `Memory` 使用);`app` 是**完整的 Android application**(独立安装、独立启动、有自己的 launcher 图标),把整套"组装 + 启动"封装在 `GatewayService` 里 — 入口有两条:① `MainActivity` 让用户能像普通 app 一样从 launcher 启动;② `GatewayService` 通过 `exported="true"` + intent-filter 暴露自定义 Action,允许其他 app(包括同 APK 的 `:app` 和第三方 app)用 `startForegroundService` 拉起 Service 跑 feishu 机器人。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | gateway-session-integration |
| 关联模块 | `app`(新,Android application,放在 `gateway/app/`,含 `MainActivity` + `GatewayService` + `DefaultAgentRunner` 三个类)、`:session`(`create` 加可选 `sessionId` 参数)、`gateway-core`(`AgentRunner` 接口不变,无文件改动) |
| 关联前置 | gateway 移植自 HermesApp(已合入主分支);`app` 模块已有的 `BuildConfig` + `local.properties` 注入模式 |
| 不在范围 | MainActivity UI 细节设计(只要有最小 launcher activity)、tools/skills/subagents 的 app 集成、gateway 平台适配器本身、多平台支持(本次只集成 feishu) |

---

## 1. 动机

`gateway-core` 当前是平台接入层框架,负责把 Feishu / Telegram / Weixin 等平台的入站消息路由给 `AgentRunner`,由 agent 跑出响应后回传。它**没有跨 turn 的对话记忆**:`AgentRunner` 拿到 `IncomingMessage` 之后,如果直接构造 `agent { ... }` 跑,用的是 `InMemoryMemory`,每条消息进来都是"白纸一张",bot 失忆。

`:session` 模块已经提供了 `Session.memory`(持久化的 `Memory` 实现,JSONL 落盘 + 内存缓存),但目前只在 `app` UI 端用。**目标**:让 `AgentRunner` 在 gateway 上下文里也能拿到 `:session.Session.memory`,把 bot 跑出的对话历史落到磁盘,重启 / 跨进程不丢。

**应用形态**:本次 `app` 不是一个 Android library,而是**完整的 Android application 模块**(独立安装、独立启动、有自己的 launcher 图标 + `applicationId`)。`GatewayService` 在它内部承载整个 gateway engine,提供两条入口:① `MainActivity` 让用户能像普通 app 一样从 launcher 启动;② `GatewayService` 通过 `exported="true"` + intent-filter 暴露自定义 Action `io.github.yeyi.agent.gateway.app.START`,允许其他 app(同 APK 的 `:app` 或第三方 app)用 `startForegroundService` 拉起。配置(LLM API key、feishu 凭证)通过 `local.properties` + `BuildConfig` 注入(与现有 `app` 模块同模式),sessionDir 通过 `context.filesDir` 取。本次只集成 feishu 平台,使用其 native 的 WebSocket 收消息机制(不引入 Ktor server / webhook)。

---

## 2. 设计原则

- **接受 gateway 现有 per-user 设计,不强行改语义**:`GatewaySessionManager` / `RateLimitHook` / `ConcurrencyController` / `isProcessing` 全部按 `sessionKey = "platform:chatId:userId"` 走;memory 也按 user 切,跟 sessionKey 对齐 — 不试图"按 chat 共享 memory"来重构 hooks/concurrency。
- **复用 `:session` 现有 API,不造新概念**:`Session.memory` 已经是 property,直接拿;不引入 `resolveMemory` 闭包、不引入独立的 `memoryId` 概念。
- **字段从现成结构取,不读 `session.key` 解析**:`gatewaySession.key` 跟 `:session.Session.id` 在 gateway 派生 sessionId 时**不耦合** — 从 `session.platform` / `session.chatId` / `session.userId` 字段现拼,即使 gateway 改 `sessionKey()` 拼装格式也不影响。
- **accountId = 部署/平台命名空间,不是业务 user**:`:session.SessionManager.accountId` 语义是"app 自身所属账户",不要用业务侧 userId(聊天对方/群成员)填充;否则群聊里"不同人触发"会变成"不同账户在用同一份存储",跟 :session 的设计前提冲突。
- **调用方只传原始值,呈现/裁剪/校验交给接收方**:`sessionName` 字段的截断/换行处理由 `:session` 自己负责,`DefaultAgentRunner` 不在调用方做 `.take(50)`。
- **整合代码归 app,`gateway-core` 保持叶子模块**:`DefaultAgentRunner` 是 app 层把 `:session.SessionManager` 和 `:agent.LlmProvider` 拼进 gateway runner 的实现细节;`gateway-core` 现状是叶子模块(不依赖 `:agent`、`:session` 或任何业务模块),只暴露 `AgentRunner` 接口让 app 注入实现。**任何**让 `gateway-core` 反向依赖 `:agent` / `:session` 的方案(包括把 `DefaultAgentRunner` 搬进 `gateway-core`、在 gateway-core 里用 `LlmProvider` / `Memory`)都会破坏叶子属性,framework 应能独立编译测试、被任何 app(无论是否用 `:agent` / `:session`)复用。
- **整条装配流水线封进 `GatewayService`,runner 只接 lambda**:`DefaultAgentRunner` 构造参数是一个 `suspend (accountId, sessionId, sessionName) -> Agent` 的 lambda — 不引入 `AgentFactory` / `AgentFactoryConfig` 这种中间包装类。Service 在 `onStartCommand` 里建好 `SessionManager` / `LlmProvider` 等所有静态依赖,然后写一个 lambda 把这些依赖闭包起来,再传给 `DefaultAgentRunner`。这样:
  - 装配的位置(Service 内部)和装配的内容(lambda 体)物理上聚在一起,可读性好
  - 测试可以直接 mock `createAgent` lambda,不必走 factory
  - 不增加不必要的间接层
- **`app` 用标准 Android application 配置,可独立启动也可被 Action 拉起**:模块用 `com.android.application` 插件 + `applicationId` + `AndroidManifest.xml` 同时声明 `<activity>`(带 `MAIN`/`LAUNCHER` intent-filter,作为独立启动入口)和 `<service>`(带自定义 `START` action 的 intent-filter 且 `exported="true"`,作为被其他 app 拉起的入口)。无 chat UI(MainActivity 只做最小"启动/停止 service"操作,UI 设计不在范围)。配置走 `local.properties` + `buildConfigField`;`sessionDir` 从 `context.filesDir` 取,不写死路径。

---

## 3. 架构

```
┌─────────────────────────────────────────────────────────┐
│  外部 app(同 APK 的 :app 或第三方 app)                    │
│  - startForegroundService(                              │
│      Intent(action=START)                               │
│            .setComponent(...GatewayService)              │
│    )                                                    │
└────────┬────────────────────────────────────────────────┘
         │ Intent(action=START, exported=true)
         ▼
┌─────────────────────────────────────────────────────────┐
│  app (Android application)                       │
│                                                          │
│  ┌────────────────────────────────────────────┐          │
│  │ MainActivity (launcher 入口)                │          │
│  │ - 显示 service 状态 / 启动 / 停止 按钮       │          │
│  └────────┬───────────────────────────────────┘          │
│           │ startForegroundService(START)                 │
│           ▼                                               │
│  ┌────────────────────────────────────────────┐          │
│  │ GatewayService                              │          │
│  │ - onStartCommand 里建所有依赖 + 启 engine   │          │
│  │ ┌──────────────────────────────────────┐   │          │
│  │ │ DefaultGatewayEngine                 │   │          │
│  │ │ + GatewaySessionManager              │   │          │
│  │ │ + HookPipeline                       │   │          │
│  │ └────────┬─────────────────────────────┘   │          │
│  │          │ process(message, session)       │          │
│  │          ▼                                │          │
│  │ ┌──────────────────────────────────────┐   │          │
│  │ │ DefaultAgentRunner (app)     │   │  ← 新增  │
│  │ │ 1. mapToSessionIds(gatewaySession)   │   │          │
│  │ │ 2. extract sessionName from message  │   │          │
│  │ │ 3. createAgent(accountId,            │   │          │
│  │ │    sid, sessionName) ──► Agent 实例  │   │  ← lambda│
│  │ └────────┬─────────────────────────────┘   │          │
│  └──────────┼─────────────────────────────────┘          │
└─────────────┼────────────────────────────────────────────┘
              │
              ▼
┌──────────────────┐
│  Feishu Adapter  │  (WebSocket / native 收消息)
└────────┬─────────┘
         │ IncomingMessage
         ▼
        ...engine 内部流转...

         │ Agent.run(...)
         ▼
┌──────────────────┐
│  :agent          │  (ReActAgent 主循环)
└────────┬─────────┘
         │ 持久化的 ChatMessage
         ▼
┌──────────────────┐
│  :session        │  (JSONL 落盘,目录 = context.filesDir/sessions)
│  Session.memory  │
└──────────────────┘
```

依赖关系:
- `gateway-core` 是叶子模块 — **不依赖** `:agent`、不依赖 `:session`(也不依赖任何业务模块),framework 应能独立编译/测试;`AgentRunner` 接口签名只用到 gateway-core 自己的类型(`IncomingMessage` / `GatewaySession` / `MessageContent` / `Result`)
- `app`(Android application,新)→ `gateway-core` + `:agent` + `:session` + `:providers:anthropic` + `:gateway:platforms:feishu` + Android SDK(`MainActivity` + `GatewayService`)
- `app`(Android application,现有)— **不依赖** `app`;通过 `Intent` 显式 `ComponentName` 触发 `app` 的 `GatewayService`(同 APK 内,显式启动,`exported="true"` 是为第三方 app 也能用)

---

## 4. 详细设计

### 4.1 :session 扩展 — `SessionManager.create` 加可选 `sessionId`(已完成)

### 4.2 app 整合 — `DefaultAgentRunner`(只接 lambda,不做工厂)

**改动文件**:
- `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/DefaultAgentRunner.kt`(新建,包名 `io.github.yeyi.agent.gateway.app`)

**架构约束**:
- `AgentRunner` 接口定义在 `gateway-core/src/main/kotlin/io/gateway/api/AgentRunner.kt`,签名不变 — framework 不知道 `:agent` / `:session` 任一存在
- `DefaultAgentRunner` 只持一个 `createAgent` lambda 和必要的 mapper 逻辑;`SessionManager` / `LlmProvider` / `agent { ... }` DSL 等具体实现全部由 `GatewayService`(见 4.3)在 lambda 体里**就地**构造,不在 runner 里出现
- 不引入 `AgentFactory` / `AgentFactoryConfig` 等中间包装类 — 装配的位置(Service)和装配的内容(lambda 体)物理上紧邻,可读性最好;测试可以直接 stub `createAgent` lambda,不必走 factory

**`DefaultAgentRunner`**(代码示意,省略 `package` / `import`):
```kotlin
class DefaultAgentRunner(
    private val createAgent: suspend (accountId: String, sessionId: String, sessionName: String) -> Agent,
) : AgentRunner {

    override suspend fun process(message: IncomingMessage, gatewaySession: GatewaySession): Result {
        val accountId = "gateway:${gatewaySession.platform.value}"   // 部署+平台 namespace
        val sessionId = "${gatewaySession.chatId}:${gatewaySession.userId}"  // 平台内会话身份
        val sessionName = (message.content as? MessageContent.Text)?.text ?: sessionId

        val agent = createAgent(accountId, sessionId, sessionName)
        // 跑 agent(用 agent.run(...)),组装 Result,返回
    }
}
```

**关键不变量**:
- `DefaultAgentRunner` 源码里**不出现** `SessionManager` / `LlmProvider` / `agent { ... }` DSL 任何之一 — 整条 session + agent 流水线对它透明,只通过 `createAgent` lambda 抽象键交互
- `accountId` 永远是 `"gateway:<platform>"` 常量(per-user 场景下也用同一个 namespace,跟 gateway 设计对齐)
- `sessionId` 由 `chatId:userId` 现拼,不读 `gatewaySession.key` 解析(避免跟 `MessageSource.sessionKey()` 拼装格式耦合)
- `sessionName` 由 runner 从 `message.content` 现取(非文本消息回落到 `sessionId`),整段原始文本原样传 lambda(截断/换行处理由 :session 内部负责)
- lambda 由 `GatewayService.onStartCommand` 一次性闭包出来,内部捕获 `sessionManager` / `llmProvider` / `coroutineScope` 等所有静态依赖

### 4.3 `GatewayService` — 装配流水线封装在 Android Service 里

**改动文件**:`gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/GatewayService.kt`(新建)

**包名 / 作用**:`io.github.yeyi.agent.gateway.app.GatewayService`,Android `Service` 子类。整个 gateway 引擎(adapter + engine + session manager + agent runner)在这里建好后启动,在 `onDestroy` 里优雅停;不在 `MainActivity.onCreate` / `Application.onCreate` 里组装。

**Service 自定义 Action**:`io.github.yeyi.agent.gateway.app.START`(在 `GatewayService` 的 `companion object` 暴露),允许被其他 app 用 `Intent(action = GatewayService.START).setComponent(...)` 触发启动。

**Service exported**:`exported="true"`(manifest 里配置,见 4.4)— 同 APK 的 `:app` 模块用 `ComponentName` 显式启动不需要 exported,但**第三方 app 拉起必须**;`intent-filter` 限定为只接受我们自己的 `START` action,过滤掉系统或其他 app 的隐式 Intent。

**`GatewayService`**(代码示意,省略 `package` / `import`):
```kotlin
class GatewayService : Service() {

    private var engine: DefaultGatewayEngine? = null
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(supervisorJob + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null      // 不提供 bound 模式
  
    override fun onCreate() {
      super.onCreate()
      serviceScope.launch { startEngine() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())          // 通知栏常驻,符合后台运行要求
        return START_STICKY                                    // 进程被回收系统会重启
    }

    private suspend fun startEngine() {
        // 1. 从 BuildConfig 抽配置(见 4.4)
        val sessionDir = File(filesDir, "sessions")
        val llmProvider = AnthropicProvider(                   // 本次只接 anthropic
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
            model = BuildConfig.ANTHROPIC_MODEL,
            baseUrl = BuildConfig.ANTHROPIC_BASE_URL,
        )
        val sessionManager = SessionManager(sessionParent = sessionDir)

        val feishuConfig = FeishuConfig(
            appId = BuildConfig.FEISHU_APP_ID,
            appSecret = BuildConfig.FEISHU_APP_SECRET,
        )
        val feishuAdapter = FeishuAdapter(feishuConfig, serviceScope)

        // 2. createAgent lambda:把 session + agent 流水线闭包到 lambda 里
        val createAgent: suspend (String, String, String) -> Agent = { accountId, sessionId, sessionName ->
            val session = try {
                sessionManager.get(accountId, sessionId)
            } catch (e: NoSuchElementException) {
                sessionManager.create(
                    accountId = accountId,
                    sessionName = sessionName,
                    sessionId = sessionId,
                )
            }
            agent {
                memory(session.memory, maxRounds = 20)
                llmProvider(llmProvider)
            }
        }

        // 3. 拼 runner → 拼 engine
        val agentRunner = DefaultAgentRunner(createAgent)
        engine = GatewayEngineBuilder()
            .withAgentRunner(agentRunner)
            .withAdapters(listOf(feishuAdapter))
            /* 其余 withHookPipeline / withSessionManager / withConcurrencyController 按需注入 */
            .build()
        engine.start()                                            // feishu native WebSocket 收消息
    }

    override fun onDestroy() {
        serviceScope.launch {
            engine?.stop()
            supervisorJob.cancel()
        }
    }

    companion object {
        const val START = "io.github.yeyi.agent.gateway.app.START"
        private const val NOTIF_ID = 1001
    }
}
```

**关键不变量**:
- `createAgent` lambda 体里**直接**构造 `SessionManager` / `LlmProvider` / `agent { ... }` DSL — 装配的位置(Service 内部)和装配的内容(lambda 体)物理上聚在一起,可读性好
- lambda 在 `onStartCommand` 里一次性闭包,捕获 `serviceScope` / `llmProvider` / `sessionManager` / `sessionDir` 等所有静态依赖
- `DefaultAgentRunner` 不接触 `SessionManager` / `LlmProvider` / `agent { ... }` 任何之一 — 整条 session + agent 流水线对它透明,只通过 lambda 抽象键交互(见 4.2)
- sessionDir 取 `context.filesDir / "sessions"`,**不**写死路径,跟随 Android 沙箱自动重定向
- 凭证(LlmProvider API key / Feishu appId+appSecret)从 `BuildConfig` 拿,BuildConfig 字段在 4.4 由 `local.properties` + `buildConfigField` 注入

### 4.4 `app` 模块配置(Android application,可独立启动)

**新增文件**:
- `settings.gradle.kts`:`include(":gateway:app")`(与 `:gateway:gateway-core` / `:gateway:platforms:feishu` 同级,放在 gateway 子项目下)
- `gateway/app/build.gradle.kts`(新建)
- `gateway/app/src/main/AndroidManifest.xml`(新建)
- `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/MainActivity.kt`(新建,launcher 入口;最小实现)
- `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/GatewayService.kt`(见 4.3)
- `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/DefaultAgentRunner.kt`(见 4.2)

**`gateway/app/build.gradle.kts`**(关键片段,`com.android.application` 插件,有 `applicationId` + `MainActivity`;实际文件顶部仍按惯例 `import java.util.Properties`,这里为简洁起见用 FQN):
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.yeyi.agent.gateway.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yeyi.agent.gateway.app"
        minSdk = 26
        versionCode = 1
        versionName = "0.1.0"

        val localProps = java.util.Properties().apply {
            val f = file("local.properties")  // 相对于 gateway/app 目录
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun raw(key: String) = localProps.getProperty(key).orEmpty()
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${raw("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_BASE_URL", "\"${raw("ANTHROPIC_BASE_URL")}\"")
        buildConfigField("String", "ANTHROPIC_MODEL", "\"${raw("ANTHROPIC_MODEL")}\"")
        buildConfigField("String", "FEISHU_APP_ID", "\"${raw("FEISHU_APP_ID")}\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"${raw("FEISHU_APP_SECRET")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":session"))
    implementation(project(":gateway:gateway-core"))
    implementation(project(":providers:anthropic"))            // 本次只接 anthropic
    implementation(project(":gateway:platforms:feishu"))       // 本次只接 feishu

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)            // Service 协程作用域需要 Main 调度
    implementation(libs.ktor.client.okhttp)                    // FeishuAdapter 内部用
}
```

**`gateway/app/src/main/AndroidManifest.xml`**(同时声明 activity + service,各有 intent-filter):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />  
  <application
        android:label="Gateway Bot"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">

        <!-- 入口 ①:launcher,让用户能从桌面图标独立启动 -->
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- 入口 ②:被其他 app 通过自定义 Action 拉起 -->
        <service
            android:name=".GatewayService"
            android:exported="true"
            android:foregroundServiceType="dataSync">
            <intent-filter>
                <action android:name="io.github.yeyi.agent.gateway.app.START" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

**`MainActivity.kt`**(最小实现,只是 service 状态显示 + 启动/停止按钮;UI 细节不在本次范围):
```kotlin
class MainActivity : ComponentActivity() {
    private val isRunning = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                Text(if (isRunning.value) "Gateway: running" else "Gateway: stopped")
                Button(onClick = ::toggleService) {
                    Text(if (isRunning.value) "Stop" else "Start")
                }
            }
        }
    }

    private fun toggleService() {
        val intent = Intent(GatewayService.START).apply {
            setClassName(this@MainActivity, GatewayService::class.java.name)
        }
        if (isRunning.value) {
            stopService(intent)
            isRunning.value = false
        } else {
            ContextCompat.startForegroundService(this, intent)
            isRunning.value = true
        }
    }
}
```

**`local.properties`** 新增字段(已存在的 `MODEL_*` 字段保留,这里只列新增项;`:app` 模块目前不读这些字段,`:gateway:app` 读):
```properties
ANTHROPIC_API_KEY=...
ANTHROPIC_BASE_URL=https://api.minimaxi.com/anthropic
ANTHROPIC_MODEL=MiniMax-M3
FEISHU_APP_ID=...
FEISHU_APP_SECRET=...
```

**为什么用 BuildConfig + local.properties**:
- 与现有 `:app` 模块同模式(见 `app/build.gradle.kts:21-30`),跨模块配置风格一致
- 凭证不入库 / 不进 commit — `local.properties` 在 `.gitignore` 里
- 编译期注入 — `BuildConfig.ANTHROPIC_API_KEY` 在运行时是普通 `String`,但源头来自 build 脚本里的字符串拼接,**不能**从命令行 / 环境变量绕过(避免运行时被改)

**为什么 Service `exported="true"` + intent-filter(只接受自定义 Action)**:
- 第三方 app 必须能拉起 Service 跑 bot — `exported="true"` 是 Android 跨 APK 启动的硬性要求
- intent-filter 限定为只接受 `io.github.yeyi.agent.gateway.app.START`(我们自己的 action),过滤掉系统 / 其他 app 的隐式 Intent;**不是** `<intent-filter>` 全空(那是 `android.intent.action.MAIN` 之类的全收,危险)
- `foregroundServiceType="dataSync"` 符合"长连接收消息"语义,Android 14+ 强制要求指定
- 同 APK 的 `:app` 模块用 `ComponentName` 显式启动也走这条路径(也 `exported="true"`,统一处理,不必为 `:app` 再开一个非 exported service)

### 4.5 其他 app 触发 `GatewayService`(同 APK 的 `:app` 或第三方 app)

**改动文件**:
- **同 APK 的 `:app` 模块**:在合适的入口(比如 `MainActivity.onCreate` / 通知栏 quick action / 应用启动器)用 `Intent` 显式 `ComponentName` 启动 `app` 的 Service;`:app` 模块**不**依赖 `:gateway:app`,只通过 action 字符串 + 类名耦合。
- **第三方 app**:用相同 action + 显式 `ComponentName`(或省略 component 走隐式 intent-filter)启动,需要本机安装 `app` APK。
- `:app/src/main/AndroidManifest.xml`:无需新增条目(不暴露 `app` 内部的 service,跨 APK 启动走 `app` manifest 里的 `<service>` exported="true")。

**触发代码**(同 APK 的 `:app` / 第三方 app 通用,示意):
```kotlin
private fun startGateway(context: Context) {
    val intent = Intent(GatewayService.START).apply {
        setClassName(
            "io.github.yeyi.agent.gateway.app",                // app 的 applicationId
            "io.github.yeyi.agent.gateway.app.GatewayService",
        )
    }
    ContextCompat.startForegroundService(context, intent)
}
```

**`:app` 怎么拿到 `GatewayService.START` action 字符串**:有两种选择
- **硬编码**:`:app` 里写一份 `const val START = "io.github.yeyi.agent.gateway.app.START"`(与 `app` 里的常量保持一致);坏处:两处都要维护,改一处忘改另一处就出 bug
- **共享常量模块**(推荐):抽一个 `:gateway:app:api` 子模块或 `:gateway:gateway-constants` 模块专门放 action / 类名字符串(只放 `const`,不放实现);`:app` 和 `:gateway:app` 都依赖它,避免重复维护
- 本次先**硬编码**:`:app` 模块与 `:gateway:app` 都在同一个代码仓库,改起来容易;后续如果 action / 类名被外部 app / 文档引用频繁,再抽共享模块

**为什么需要 `setClassName`**:第三方 app 走隐式 `Intent(action = ...)` 也能拉起(`<service>` 的 intent-filter 暴露了 action);但**显式 `ComponentName` 启动更稳** — 不依赖 intent-filter 匹配、不怕 service 被同名同 action 的其他 APK 抢去。`:app` 和 `app` 走同一 APK,显式启动是允许的(同进程同 UID)。

**为什么用 `startForegroundService`**:Android 8.0 (API 26) 起,后台启动 service 必须用 foreground service;`GatewayService.onStartCommand` 内部 `startForeground(...)` 必须配套调用(见 4.3)。`app` 的 `minSdk = 26`(见 4.4),所以统一走 `startForegroundService` 路径。

---

## 5. 错误处理

| 场景 | 处理 |
|------|------|
| `createAgent` lambda 内 `sessionManager.get` 抛 `NoSuchElementException` | 正常路径,在 lambda 内落入 `sessionManager.create` |
| `sessionManager.create` 失败(磁盘满 / 权限) | lambda 内异常上抛,`DefaultAgentRunner.process` 不捕获;platform 适配器拿到 `Result.Failure`,决定是否重发;`GatewaySessionManager.markProcessingComplete` 触发,释放并发槽 |
| `Memory` 内部 IO 失败(写 JSONL) | `:session` 自身异常上抛;`AgentRunner` 包装成 `Result.Failure`,平台侧决定是否重发 |
| `GatewayService.onStartCommand` 异常(配置缺失 / engine 启动失败) | `serviceScope` 协程内抛,记 log;通知栏常驻让用户看到 service 仍在跑;`onDestroy` 兜底 cancel `supervisorJob`;`START_STICKY` 让系统能自动拉起 |
| `FeishuAdapter.connect()` 失败(token 错误 / WebSocket 连不上) | adapter 内部 `ConnectResult.Failure`,gateway engine 收到后**不**重试无限循环;用户查通知栏 / log 定位 |
| `app` 模块从 `startForegroundService` 拉到 service 但 service 5s 内未 `startForeground(...)` | 系统抛 `ForegroundServiceDidNotStartInTimeException`;`onStartCommand` 第一行必须先 `startForeground(...)`(见 4.3) |
| `runTest` 路径无 IO 异常 | 用 `FakeLlmProvider` + 临时目录 + 直接调 lambda,不走 Service |

不在范围:消息重试 / 死信队列 / 会话过期清理 / Service 保活策略 — 这些是 app 装配时的策略选择,不在本次整合设计内。

---

## 6. 测试

| 文件 | 内容 |
|------|------|
| `app/src/test/kotlin/.../CreateAgentLambdaTest.kt` | 不存在 `AgentFactory` 类,改为**直接测试 lambda 体**:用临时目录 `File(tmp)` + `FakeLlmProvider` 构造 `SessionManager` + `LlmProvider`,写一个跟 `GatewayService` 里同形的 `createAgent` lambda,验证 lambda 首次调用走 `create` 分支建出新 session 且 `session.name == sessionName`、再次调用走 `get` 分支命中已存在 session;`session.memory` 在两次调用间持久化。**这套测试覆盖的就是 `GatewayService` 里那几行 lambda 体**(因为 lambda 是无依赖注入的纯逻辑,可以独立测) |
| `app/src/test/kotlin/.../DefaultAgentRunnerTest.kt` | 用 mock `createAgent` lambda(或记录调用的 fake lambda)验证:`process()` 把 `gatewaySession.platform` / `chatId` / `userId` 正确映射成 `accountId="gateway:<platform>"` / `sessionId="chatId:userId"`,并把 message text(或非文本回落 `sessionId`)作为 `sessionName` 转发给 `createAgent` |
| `app/src/test/kotlin/.../GatewayServiceAssemblyTest.kt`(可选) | 用 `Robolectric` 或自建 `Service` 替身(`Application` + `Context` mock)调 `onStartCommand`,验证:依赖图能完整拼装、`engine.start()` 被调用;不真起 engine / adapter,只验证装配路径 |
| `:session` 现有测试 | 加一个 `createWithCustomSessionId` 测试:验证 `Manager.create(accountId, name, "fixed")` 后续 `Manager.get(accountId, "fixed")` 命中;`Manager.create(accountId, name)`(旧调用)继续走 UUID 路径,行为不变 |
| `app/src/test/kotlin/.../StartGatewayIntentTest.kt`(可选) | 验证 `startGateway()` 发出的 Intent `action == GatewayService.START` 且 `component.className == "io.github.yeyi.agent.gateway.app.GatewayService"`;不真拉起 service |

不写:
- 端到端飞书集成测试(需要真实平台凭证,在 `app` 文档里给出手动验证步骤即可 — 启动 app → service 自动起 → 飞书群里 @bot 发消息 → 验证回复 + 重启后 bot 仍记得上文)
- Service 进程回收 / 前台通知保活 / Doze 模式相关测试(这些是 Android 框架行为,不是本次设计目标)

---

## 7. 关键决策回顾(避免重蹈)

本次设计在多轮反馈中收敛,以下是**已否决**的方案及否决原因(防止后续 reviewer / agent 重新提):

| 否决方案 | 否决原因 |
|---------|---------|
| `accountId = session.userId` | 语义错位(accountId 是 app 自身账户,不是聊天对方);群聊里"不同人触发 = 不同账户"会破坏 :session 假设 |
| `accountId = "gateway"` 单一常量 | 缺少平台隔离,查/删/统计需要二次过滤 |
| `sessionId = session.key`(直接读) | 跟 `MessageSource.sessionKey()` 拼装格式耦合,格式一变 `AgentRunner` 解析逻辑跟着爆 |
| 在 `AgentRunner` 里加 `resolveMemory: (memoryId) -> Memory` 闭包 | 多余间接层 — `Session.memory` 已经是 property,直接拿 |
| 在 `GatewaySession` 加 `conversationId` / `memoryId` 字段 | 已有 `key` 够用,加新字段引入 schema 迁移成本 |
| 把 `SessionManager.create` 拆成 `create` + `createWithId` | 扩展(必填参数)比新增方法干净,API 表面更小 |
| 给 `create` 加 `sessionId: String? = null` 默认参数 | 默认值是死分支(所有调用方都会传 sid),徒增认知负担,改用必填参数 |
| 给 `Repository.createSession` 也加 `sessionId: String? = null` 默认 | Repository 是底层,只有 Manager 一个调用方,默认值会藏"自建 id vs 外部 id"决策,应该让 Manager 在调用点显式表态;Manager 公开 API 仍保留默认 |
| 在 `AgentRunner` 里做 `name.take(50)` | 接收方责任,调用方不该越俎代庖 |
| 改 gateway 让群聊共享一份 memory | 牵动 hooks / rate limit / concurrency 全套,远超 AgentRunner 职责范围 |
| 在 `gateway-core` 里建 `DefaultAgentRunner` 或让 `gateway-core` 依赖 `:agent` / `:session` | `gateway-core` 应保持叶子模块,framework 的边界由依赖方向定义;让 gateway-core 反向依赖任一业务模块都会污染 framework,堵住被其他 app 复用的可能 |
| `DefaultAgentRunner` 直接持 `SessionManager` + `LlmProvider` + tools 等所有静态依赖 | 把整条 session + agent 流水线的依赖都摊在 runner 上,runner 测试要 mock 一堆东西,职责过重;`createAgent` lambda 让 runner 只持一个抽象键入口,测试和复用都更干净 |
| 引入 `AgentFactory` / `AgentFactoryConfig` 包装类承载装配逻辑 | 多余间接层;lambda 已经能把 lambda 体里的所有依赖闭包出来,再加一个类只多一处声明 / 一处测试目标;装配的位置(`GatewayService`)和装配的内容(lambda 体)紧邻,可读性比拆成 factory 类更好 |
| `app` 用 `com.android.application` + `Main.kt` 跑 JVM 长驻服务 | Android app 模块里塞 `application` 插件 + Ktor server 是一团混乱(manifest / `application` / 通知栏 / 前台 service 谁来拉 全要重排);`app` 走 `com.android.library` + `Service` 是 Android 原生做法,所有进程生命周期由系统托管 |
| `app` 用 `application` 插件 + Ktor server 收 webhook | feishu 已支持 native WebSocket 收消息,引入 Ktor 还得开端口 / 配反代 / 跑 HTTPS,徒增运维负担;Service + WebSocket 是 feishu 官方推荐路径 |
| 配置走 env var / `application.yml` / `SharedPreferences` | 三种方案都跟现有 `app` 模块 `local.properties` + `BuildConfig` 模式不一致;BuildConfig 编译期注入是 Android 圈最干净的做法,凭证也不入库 |
| `GatewayService` 提供 bound mode(`onBind` 返回 `IBinder` 给 app 查状态) | 本次只跑"启动后一直跑、不停"的模式;bound mode 引入 lifecycle 复杂度(谁 unbind / 多 client 同步状态),首版不需要 |
| `accountId` 用 `applicationId`(`"io.github.yeyi.agent.app"`) | 跟 :session 的"账户"语义对不上 — `applicationId` 是 app 包名,不是"app 所属账户";`"gateway:feishu"` 才是"这个 app 在 feishu 平台上的 namespace",跟 :session 假设一致 |

---

## 8. 未来工作(不在本次)

- `app` 接 MCP / Tools / Skills / Subagent(目前 app UI 那套 demo 不在 app 里)
- 平台凭证管理(目前是 `BuildConfig` 字段,生产可能要接 secret manager)
- 多 gateway 部署下的会话共享(RedisBackedMemory 等)
- Hook 层的"白名单"扩展(目前是 RateLimitHook,后面可加 MessageFilterHook 决定哪些消息进 memory)
- `app` 的 graceful shutdown(目前 `onDestroy` 直接 `engine?.stop()`,没等 in-flight message 跑完)
- 前台通知栏的快速操作按钮("停止 bot" / "查看状态" PendingIntent)
- `app` 模块用 instrumentation test 跑真 Service lifecycle(目前只覆盖装配路径,不真测 service 启动)
