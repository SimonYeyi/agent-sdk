# Gateway JVM Daemon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone JVM daemon — `:gateway:jvm` — that mirrors the Android `:gateway:app` engine wiring, producing a single `java -jar`-runnable feishu bot without Android runtime.

**Architecture:** New Gradle `kotlin.jvm` module with `application` + `shadow` plugins. Four Kotlin files under `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/`: `Main.kt` (entry + shutdown hook), `GatewayDaemon.kt` (scope/engine holder, start/stop), `GatewayDaemonConfig.kt` (properties + env override + fail-fast validation), `DefaultAgentRunner.kt` (verbatim copy of Android version). No unit tests (matches existing `:gateway:app` precedent).

**Tech Stack:** Kotlin 2.2.0, JVM toolchain 21, Gradle `application` plugin + `com.gradleup.shadow` 8.3.11, existing SDK modules (`:agent :session :hook :providers:anthropic :gateway:core :gateway:platforms:feishu`). Config via `java.util.Properties`. Logging: stdout via println (no extra dep). (8.3.11 chosen because the project uses Gradle 9.2.1; shadow 8.3.5 fails with `propertyName=mainClassName` on `application.mainClass` set under Gradle 9; 8.3.10+ includes the Gradle 9 backport.)

---

## Commit Policy (overrides default template)

Per user preference (`feedback_no_eager_commits`), **do NOT commit between tasks**. All changes accumulate locally; a single atomic commit is created at the very end (Task 8) covering:
- All files added/modified by Tasks 1–7
- The gateway-jvm spec at `docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md` (already in working tree, not yet committed)

The standard "Step 5: Commit" from the writing-plans template is therefore omitted in every task. Verification steps use `./gradlew` commands only.

---

## Global Constraints

(Verbatim from `docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md`)

- Module name `:gateway:jvm`; root package `io.github.yeyi.agent.gateway.jvm`. Android `:gateway:app` is **not modified** in this work.
- Kotlin (NOT Java-language); JVM-only (no Android, no `Context.filesDir`, no `BuildConfig`).
- Mirror `GatewayService.startEngine` (`gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/GatewayService.kt:50-83`) line-for-line, replacing `BuildConfig.*` / `context.filesDir` with config-loaded values.
- Only register `:gateway:platforms:feishu`. Do not introduce telegram/weixin.
- Plugin set: `org.jetbrains.kotlin.jvm` + `application` + `com.gradleup.shadow`.
- JVM toolchain 21 (matches `:gateway:core/build.gradle.kts`).
- Config required keys: `anthropic.api.key`, `anthropic.base.url`, `anthropic.model`, `feishu.app.id`, `feishu.app.secret`. Config optional keys with defaults: `session.storage.dir = "./data/gateway/sessions"`, `gateway.max.concurrent.sessions = 10`. Spec's `anthropic.api.timeout.seconds` is dropped (YAGNI): `AnthropicProvider` has no timeout ctor param and defaults to 60s via `HttpTimeout`.
- Env var override mapping: `k` (lower, dots) ↔ `k.UPPER.SNAKE`. Explicit-path precedence: `GATEWAY_CONFIG` → file required; cwd default → file optional.
- Lifecycle: `main()` → `daemon.start()` → `runBlocking { engine.start() }`; `Runtime.addShutdownHook { daemon.stop() }`.
- Single atomic commit at end covering implementation + spec doc.

---

## Task 1: Module Scaffold

**Files:**
- Modify: `settings.gradle.kts:41` (append `:gateway:jvm`)
- Modify: `gradle/libs.versions.toml` (append `shadow` plugin entry)
- Create: `gateway/jvm/build.gradle.kts` (full content below)
- Create: `gateway/jvm/src/main/kotlin/.gitkeep` (directory placeholder so Task 2 can land)

**Interfaces (provided to later tasks):**
- Produces: gradle project `:gateway:jvm` recognized; `shadowJar` task available; running `./gradlew :gateway:jvm:tasks` lists `run`, `shadowJar`, `compileKotlin`.

### Step 1.1: Append include to settings

Edit `settings.gradle.kts` line 41 area. The current state (verified 2026-07-01) ends:
```kotlin
include(":gateway:app")
```
Append a new line:
```kotlin
include(":gateway:jvm")
```

### Step 1.2: Add shadow plugin to version catalog

Edit `gradle/libs.versions.toml`. Under `[versions]`, append:
```toml
shadow = "8.3.11"
```

Under `[plugins]`, append:
```toml
shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
```

(The `application` plugin is Gradle built-in; it will be referenced directly in build.gradle.kts without a catalog alias.)

### Step 1.3: Create build.gradle.kts skeleton

Create file `gateway/jvm/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.shadow)
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("io.github.yeyi.agent.gateway.jvm.MainKt")
}

dependencies {
    implementation(project(":gateway:core"))
    implementation(project(":gateway:platforms:feishu"))
    implementation(project(":providers:anthropic"))
    implementation(project(":session"))
    implementation(project(":agent"))
    implementation(project(":hook"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
}

tasks.shadowJar {
    archiveBaseName.set("gateway-jvm")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
```

(`kotlinx-coroutines-core`, `ktor-client-core`, `ktor-client-cio` arrive transitively via `:gateway:core`'s `api` and `:providers:anthropic`'s `api` respectively — do not redeclare.)

### Step 1.4: Create directory placeholder

Create `gateway/jvm/src/main/kotlin/.gitkeep` (empty file, just to register the directory).

### Step 1.5: Verify gradle recognizes the project

Run from repo root:
```bash
./gradlew :gateway:jvm:tasks --no-daemon 2>&1 | grep -E "run - |shadowJar - |compileKotlin - "
```

Expected output (snippet): task names listed under `:gateway:jvm - JVM daemon entry` heading:
```
run - Runs this project as a JVM application
shadowJar - Create a combined JAR of project and shadow dependencies
compileKotlin - Compiles the Kotlin compilation 'main'
```
Plus warnings are acceptable (e.g., missing main source files). The call must NOT fail with `Project with path ':gateway:jvm' could not be found`.

If it fails with a missing-class complaint from `application` plugin (because `MainKt` doesn't exist yet), that's OK for this task — that's resolved by Tasks 2-6. As long as `settings.gradle.kts` and `build.gradle.kts` parse successfully.

---

## Task 2: Config Layer

**Files:**
- Create: `gateway/jvm/src/main/resources/application.properties.example`
- Create: `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemonConfig.kt`

**Interfaces (provided to later tasks):**
- Produces:
  - `data class GatewayDaemonConfig(anthropicApiKey: String, anthropicBaseUrl: String, anthropicModel: String, feishuAppId: String, feishuAppSecret: String, sessionStorageDir: String, maxConcurrentSessions: Int)`
  - Companion `fun load(): GatewayDaemonConfig`
  - Companion `private fun envOverride(props: Properties, key: String)` — internal but documented in code
  - Throws `IllegalStateException` with message listing all missing required keys if validation fails

### Step 2.1: Write the resources file

Create `gateway/jvm/src/main/resources/application.properties.example`:

```properties
# ---------- Required ----------
# Anthropic LLM provider credentials.
anthropic.api.key=
anthropic.base.url=https://api.anthropic.com
anthropic.model=claude-sonnet-4-6

# Feishu open platform app credentials.
feishu.app.id=
feishu.app.secret=

# ---------- Optional ----------
# Directory for FileSessionManager JSONL storage.
# Resolved relative to current working directory.
session.storage.dir=./data/gateway/sessions

# Cap on concurrent agent sessions (per-engine).
gateway.max.concurrent.sessions=10

# ---------- Override behavior ----------
# Any of the above keys may be overridden by a system environment variable
# whose name is the uppercased, dot-replaced form, e.g.
#   anthropic.api.key  <->  ANTHROPIC_API_KEY
#   feishu.app.id      <->  FEISHU_APP_ID
# If GATEWAY_CONFIG is set, its path is used; otherwise ./application.properties.
# Missing required keys cause the daemon to fail fast at startup.
```

### Step 2.2: Write GatewayDaemonConfig.kt

Create `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemonConfig.kt`:

```kotlin
package io.github.yeyi.agent.gateway.jvm

import java.io.File
import java.util.Properties

public data class GatewayDaemonConfig(
    val anthropicApiKey: String,
    val anthropicBaseUrl: String,
    val anthropicModel: String,
    val feishuAppId: String,
    val feishuAppSecret: String,
    val sessionStorageDir: String,
    val maxConcurrentSessions: Int,
) {
    public companion object {
        private const val GATEWAY_CONFIG_ENV = "GATEWAY_CONFIG"
        private const val DEFAULT_SESSION_DIR = "./data/gateway/sessions"
        private const val DEFAULT_MAX_CONCURRENT = 10

        private val REQUIRED_KEYS = listOf(
            "anthropic.api.key",
            "anthropic.base.url",
            "anthropic.model",
            "feishu.app.id",
            "feishu.app.secret",
        )

        /** Load config from properties file + env override, then validate. */
        public fun load(): GatewayDaemonConfig {
            val props = loadProperties()
            return assemble(props)
        }

        private fun loadProperties(): Properties {
            val props = Properties()
            val explicitPath = System.getenv(GATEWAY_CONFIG_ENV)
            val path = explicitPath ?: "application.properties"
            val file = File(path)
            when {
                explicitPath != null && !file.exists() -> {
                    throw IllegalStateException(
                        "GATEWAY_CONFIG=$explicitPath does not exist; refusing to fall back to defaults",
                    )
                }
                file.exists() -> file.inputStream().use { props.load(it) }
            }
            return props
        }

        private fun assemble(props: Properties): GatewayDaemonConfig {
            REQUIRED_KEYS.forEach { envOverride(props, it) }

            val missing = REQUIRED_KEYS.filter { props.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    buildString {
                        append("Missing required config keys: ")
                        append(missing.joinToString(", "))
                        append("\nSet via ")
                        append(missing.joinToString("/") { it.uppercase().replace('.', '_') })
                        append(" env vars, or in application.properties (path: ")
                        append(System.getenv(GATEWAY_CONFIG_ENV) ?: "application.properties")
                        append(")")
                    },
                )
            }

            return GatewayDaemonConfig(
                anthropicApiKey = props.getProperty("anthropic.api.key").trim(),
                anthropicBaseUrl = props.getProperty("anthropic.base.url").trim(),
                anthropicModel = props.getProperty("anthropic.model").trim(),
                feishuAppId = props.getProperty("feishu.app.id").trim(),
                feishuAppSecret = props.getProperty("feishu.app.secret").trim(),
                sessionStorageDir = props.getProperty("session.storage.dir", DEFAULT_SESSION_DIR),
                maxConcurrentSessions = props.getProperty("gateway.max.concurrent.sessions", DEFAULT_MAX_CONCURRENT.toString())
                    .toIntOrNull() ?: DEFAULT_MAX_CONCURRENT,
            )
        }

        /** Override property value with env var (uppercase, dot→underscore) if present. */
        private fun envOverride(props: Properties, key: String) {
            val envKey = key.uppercase().replace('.', '_')
            val envVal = System.getenv(envKey)
            if (!envVal.isNullOrBlank()) {
                props.setProperty(key, envVal)
            }
        }
    }
}
```

### Step 2.3: Verify compile

Run from repo root:
```bash
./gradlew :gateway:jvm:compileKotlin --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. The build will warn that `MainKt` doesn't exist (it's referenced from `application.mainClass`) — that warning is acceptable and resolves in Task 6. If compile fails with `Unresolved reference` errors, fix import/path typos in the file.

---

## Task 3: Agent Runner

**Files:**
- Create: `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/DefaultAgentRunner.kt`

**Interfaces (provided to later tasks):**
- Produces: `class DefaultAgentRunner(createAgent: suspend (accountId: String, sessionId: String, sessionName: String) -> Agent) : AgentRunner` — same public surface as Android `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/DefaultAgentRunner.kt:11-29`. The implementation is verbatim except the package.

### Step 3.1: Write DefaultAgentRunner.kt

Create `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/DefaultAgentRunner.kt`:

```kotlin
package io.github.yeyi.agent.gateway.jvm

import io.github.yeyi.agent.Agent
import io.gateway.api.AgentRunner
import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import io.github.yeyi.agent.awaitResult
import kotlinx.coroutines.flow.Flow

class DefaultAgentRunner(
    private val createAgent: suspend (accountId: String, sessionId: String, sessionName: String) -> Agent,
) : AgentRunner {

    override suspend fun process(
        message: IncomingMessage,
        session: GatewaySession
    ): AgentRunner.Result {
        val accountId = "gateway:${session.platform.value}"
        val sessionId = "${session.chatId}:${session.userId}"
        val sessionName = (message.content as? MessageContent.Text)?.text ?: sessionId

        val agent = createAgent(accountId, sessionId, sessionName)
        val agentResult = agent.run(message.content.toString()).awaitResult()
        return AgentRunner.Result.Success(MessageContent.Text(agentResult.message.content!!))
    }

    override fun observeStream(sessionKey: String): Flow<String>? = null
}
```

(The body is identical to `gateway/app/src/main/kotlin/io/github/yeyi/agent/gateway/app/DefaultAgentRunner.kt`, only the package declaration differs. Verified by reading that file at planning time.)

### Step 3.2: Verify compile

Run from repo root:
```bash
./gradlew :gateway:jvm:compileKotlin --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. If `Unresolved reference: AgentRunner`, `GatewaySession`, etc. — confirm `:gateway:core` is on the classpath (already declared in Task 1.3); if missing, re-check Task 1.3 deps block.

---

## Task 4: Daemon Lifecycle

**Files:**
- Create: `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemon.kt`

**Interfaces (provided to later tasks):**
- Produces:
  - `class GatewayDaemon(private val config: GatewayDaemonConfig)`
  - `fun start()` — blocks (via `runBlocking`) until engine stops, runs engine in dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
  - `fun stop()` — idempotent, calls `engine.stop()` and cancels scope
- Consumes: `GatewayDaemonConfig` (Task 2), `DefaultAgentRunner` (Task 3), `AnthropicProvider`, `:session.SessionManager`, `:gateway:engine.GatewayEngineBuilder`, `FeishuAdapter`, `FeishuConfig`.

### Step 4.1: Write GatewayDaemon.kt

Create `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemon.kt`:

```kotlin
package io.github.yeyi.agent.gateway.jvm

import io.gateway.engine.GatewayEngineBuilder
import io.gateway.platform.feishu.FeishuAdapter
import io.gateway.platform.feishu.FeishuConfig
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.session.SessionManager
import io.github.yeyi.agent.hook.HookPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GatewayDaemon(private val config: GatewayDaemonConfig) {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var engine: io.gateway.api.GatewayEngine? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("GatewayDaemon already started")
        }
        println("[gateway-jvm] starting with anthropic.model=${config.anthropicModel}, sessionDir=${config.sessionStorageDir}")

        val llmProvider = AnthropicProvider(
            apiKey = config.anthropicApiKey,
            model = config.anthropicModel,
            baseUrl = config.anthropicBaseUrl,
        )

        val baseDir = File(config.sessionStorageDir).also { it.mkdirs() }
        val sessionManager = SessionManager(baseDir, HookPipeline())

        val feishuAdapter = FeishuAdapter(
            config = FeishuConfig(
                appId = config.feishuAppId,
                appSecret = config.feishuAppSecret,
            ),
            coroutineScope = scope,
        )

        val createAgent: suspend (String, String, String) -> io.github.yeyi.agent.Agent =
            { accountId, sessionId, sessionName ->
                val session = sessionManager.getOrCreate(accountId, sessionName, sessionId)
                agent {
                    memory(session.memory)
                    llmProvider(llmProvider)
                }
            }

        val builtEngine = GatewayEngineBuilder()
            .withFileSessionStorage(baseDir)
            .withAgentRunner(DefaultAgentRunner(createAgent))
            .build()

        builtEngine.registerAdapter(feishuAdapter)
        engine = builtEngine

        runBlocking {
            scope.launch { builtEngine.start() }.join()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        println("[gateway-jvm] stopping")
        runBlocking {
            scope.launch {
                runCatching { engine?.stop() }
            }.join()
        }
        scope.cancel()
    }
}
```

Notes on this implementation:
- `HookPipeline()` is a factory function defined at `hook/src/main/kotlin/io/github/yeyi/agent/hook/DefaultHookPipeline.kt:210` — same call pattern as Android `GatewayService.startEngine`.
- Account/session/sessionName derivation in `DefaultAgentRunner` (Task 3) is unchanged from Android; the createAgent lambda here simply plumbs the `accountId/sessionId/sessionName` triple into agent construction. **Do not** truncate `sessionName` or normalize it; the `:session` layer handles that (per established convention in `2026-06-25-gateway-app-session-integration-design.md`).
- `runBlocking { scope.launch { ... }.join() }` ensures `main()` keeps the JVM alive while the engine runs. Withdraw of `engine.start()` is the only way `main` returns under normal operation.
- `runBlocking` is acceptable here because the daemon is the only consumer of the calling thread. If you observe "Inappropriate blocking method call" warnings in stricter lint modes, they can be suppressed at the call site (YAGNI: don't).

### Step 4.2: Verify compile

Run from repo root:
```bash
./gradlew :gateway:jvm:compileKotlin --no-daemon 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. Common errors and what they mean:
- `Unresolved reference: agent` — the `agent { ... }` DSL function is defined in `:agent`; the import `io.github.yeyi.agent.agent` is correct. If missing, re-check Task 1.3 deps include `:agent`.
- `Unresolved reference: gatewayLog` or similar — those are inside `:gateway:platforms:feishu`'s source. Should compile given the dep is declared.

---

## Task 5: Main Entry

**Files:**
- Create: `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/Main.kt`

**Interfaces (provided to later tasks):**
- Produces:
  - `fun main()` — top-level entry; creates daemon, attaches shutdown hook, calls `daemon.start()`.
  - Class name `MainKt` (Kotlin convention) — referenced from `application.mainClass` in Task 1.3.

### Step 5.1: Write Main.kt

Create `gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/Main.kt`:

```kotlin
package io.github.yeyi.agent.gateway.jvm

fun main() {
    val config = GatewayDaemonConfig.load()
    val daemon = GatewayDaemon(config)

    Runtime.getRuntime().addShutdownHook(Thread {
        daemon.stop()
    })

    daemon.start()
}
```

Note: `Runtime.getRuntime().addShutdownHook` is idempotent in the sense that attaching the same Thread object twice is rejected — but each JVM run only attaches once, so this is fine. The hook fires on SIGTERM / SIGINT / normal `System.exit`.

### Step 5.2: Verify assemble + run tasks resolve

Run from repo root:
```bash
./gradlew :gateway:jvm:tasks --no-daemon --group=application 2>&1 | tail -20
```

Expected: shows `run` task under the `:gateway:jvm` group. Then:
```bash
./gradlew :gateway:jvm:assemble --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` and `gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT.jar` exists. (The fat jar appears after Task 7 / shadowJar invocation.)

---

## Task 6: Smoke Verify (No Real Feishu)

**Goal:** Confirm the daemon gets past `GatewayDaemonConfig.load()` validation and reaches engine start-up code. We do not connect to a real feishu cloud here (no creds); we expect either a controlled hang at `engine.start()` or a fast error from feishu's missing-credentials path. The smoke verifies fail-fast messaging is intact.

### Step 6.1: Fail-fast when config is missing

Run from repo root with NO env vars, NO `application.properties`:
```bash
./gradlew :gateway:jvm:run --no-daemon 2>&1 | tail -30
```

Expected: process exits within ~5 seconds with `IllegalStateException` whose message contains:
```
Missing required config keys: anthropic.api.key, anthropic.base.url, anthropic.model, feishu.app.id, feishu.app.secret
Set via ANTHROPIC_API_KEY/ANTHROPIC_BASE_URL/ANTHROPIC_MODEL/FEISHU_APP_ID/FEISHU_APP_SECRET env vars...
```

If the message doesn't list all five keys, fix the `REQUIRED_KEYS` ordering or the `envOverride` logic. If it says only some are missing while env vars are set, debug env var resolution.

### Step 6.2: Fail-fast when GATEWAY_CONFIG points to a non-existent file

Run:
```bash
GATEWAY_CONFIG=/tmp/does-not-exist.properties ./gradlew :gateway:jvm:run --no-daemon 2>&1 | tail -10
```

Expected: process exits with `IllegalStateException` whose message contains:
```
GATEWAY_CONFIG=/tmp/does-not-exist.properties does not exist; refusing to fall back to defaults
```

If it instead says "Missing required config keys", the explicit-vs-default branch in `loadProperties()` is wrong; re-check Task 2.2.

### Step 6.3: Reach engine.start() (env-var populated, optional short timeout)

This step requires **all five required config values**. For local smoke we set dummy values; we don't expect a real feishu connection to succeed, but we want to see the daemon get past `GatewayDaemon.start()`'s provisioning and reach `engine.start()` without exceptions during the Anvil block:
```bash
ANTHROPIC_API_KEY=sk-test \
ANTHROPIC_BASE_URL=https://api.anthropic.com \
ANTHROPIC_MODEL=claude-sonnet-4-6 \
FEISHU_APP_ID=demo_id \
FEISHU_APP_SECRET=demo_secret \
timeout 8 ./gradlew :gateway:jvm:run --no-daemon 2>&1 | tail -40
```

Expected: program prints `[gateway-jvm] starting with anthropic.model=claude-sonnet-4-6, sessionDir=./data/gateway/sessions` and then hangs for the duration of the timeout (8s) — feishu OAuth will likely fail without real credentials and may print errors to stderr, **that's acceptable**. The test passes if:
1. The first `[gateway-jvm] starting` line appears within 2 seconds
2. The process did NOT exit before the 8s timeout with an `IllegalStateException` (config validation passed)
3. No `Unresolved reference` / `ClassNotFoundException` from Kotlin classes (proves wiring is correct)

If the program reaches `engine.start()` but feishu dies immediately, verify the daemon's `GatewayDaemon.stop()` is still callable manually via `jstack <pid>` — not required for this task, just a sanity backup.

---

## Task 7: Shadow Fat Jar

**Goal:** Verify the distributable artifact is buildable and contains all needed classes.

### Step 7.1: Build the fat jar

Run from repo root:
```bash
./gradlew :gateway:jvm:shadowJar --no-daemon 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` and the file `gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT-all.jar` exists.

### Step 7.2: Confirm Main-Class manifest

Run:
```bash
unzip -p gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT-all.jar META-INF/MANIFEST.MF | head -10
```
On Windows (no `unzip`): the equivalent in PowerShell is:
```powershell
Select-String -Path 'gateway\jvm\build\libs\gateway-jvm-0.1.0-SNAPSHOT-all.jar' -Pattern 'Main-Class' -Binary
```
Or use any archive tool to inspect `META-INF/MANIFEST.MF`.

Expected: a line containing `Main-Class: io.github.yeyi.agent.gateway.jvm.MainKt`.

### Step 7.3: Run the fat jar directly

```bash
java -jar gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT-all.jar 2>&1 | head -5
```

Expected: same `Missing required config keys: ...` message as Task 6.1 (no env vars set in this fresh shell). If output differs, the shadow jar lost an entry — re-check `application` and `shadow` plugin config.

---

## Task 8: Final Atomic Commit

**Goal:** Single atomic commit covering:
- `:gateway:jvm` module (Tasks 1-7)
- The gateway-jvm spec doc `docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md` (already in working tree from brainstorming phase)

### Step 8.1: Verify git state pre-commit

```bash
git status --short
```

Expected (exact paths may vary but file types must match):
```
 M docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md
?? gateway/jvm/
?? docs/superpowers/plans/2026-07-01-gateway-jvm-entry.md
```

(working tree only, NOTHING committed yet between Tasks 1 and 7)

Note: This plan file `docs/superpowers/plans/2026-07-01-gateway-jvm-entry.md` is included in the same commit. The spec doc MUST be staged.

If anything other than the above is in `git status`, stop and investigate: pre-existing untracked work shouldn't slip into this commit.

### Step 8.2: Stage all files

```bash
git add \
  gateway/jvm/build.gradle.kts \
  gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/Main.kt \
  gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemon.kt \
  gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/GatewayDaemonConfig.kt \
  gateway/jvm/src/main/kotlin/io/github/yeyi/agent/gateway/jvm/DefaultAgentRunner.kt \
  gateway/jvm/src/main/resources/application.properties.example \
  settings.gradle.kts \
  gradle/libs.versions.toml \
  docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md \
  docs/superpowers/plans/2026-07-01-gateway-jvm-entry.md
```

If `git status` shows additional `.gitkeep` files in the new module dir, add them too. If `git status` shows `.idea/` modifications, **do not** stage those (user-managed IDE config).

### Step 8.3: Verify staged content

```bash
git diff --staged --stat
```

Expected: ~12 files, ~250-400 lines added. The four new Kotlin files should add up to ~150 lines. The spec doc is ~236 lines; the plan is ~300+ lines.

### Step 8.4: Commit

```bash
git commit -m "$(cat <<'EOF'
feat(gateway): 新增 :gateway:jvm 守护进程入口

在 :gateway:app (Android) 之外加一个非 Android 的 JVM 守护进程,
镜像 GatewayService.startEngine 的装配链路(AnthropicProvider +
:session.SessionManager + FeishuAdapter + DefaultAgentRunner +
GatewayEngineBuilder)。配置走 application.properties + 环境变量
覆盖,必填键缺失 fail-fast。application + shadow 打 fat jar,
java -jar 即可独立运行。Android :gateway:app 不修改。

spec: docs/superpowers/specs/2026-07-01-gateway-jvm-entry-design.md
plan: docs/superpowers/plans/2026-07-01-gateway-jvm-entry.md

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

Expected: commit hash returned; `git log --oneline -1` shows the new commit on top.

### Step 8.5: Verify clean working tree

```bash
git status
```

Expected: `nothing to commit, working tree clean`. If anything is unstaged, do NOT commit it again — surface to the user.

### Step 8.6: Report

Tell the user:
- Commit hash
- Path to spec and plan docs
- Final smoke-test result from Task 6.3 (whether daemon reached engine.start)

---

## Self-Review Notes

### Spec coverage
| Spec section | Covered by |
|---|---|
| §3.1 module layout (settings include + build.gradle.kts) | Task 1 |
| §3.2 file responsibilities | Tasks 2, 3, 4, 5 |
| §3.3 data flow | Tasks 3, 4, 5 |
| §4 assembly mirror | Tasks 3, 4 (with §4 row mapping) |
| §5.1 example properties | Task 2 Step 2.1 |
| §5.2 load order: path resolution + env override | Task 2 Step 2.2 |
| §5.3 GatewayDaemonConfig output | Task 2 Step 2.2 |
| §6 lifecycle: main + addShutdownHook | Tasks 4, 5 |
| §7.1 build.gradle.kts essentials | Task 1 Step 1.3 |
| §7.2 run modes | Task 7 |
| §8 test policy (no tests) | Honored (Tasks use gradle verification only) |
| §9 risk (no public abstraction for Android tie-in) | Honored (no module split) |
| §10 acceptance criteria | Tasks 6, 7 |

### Dropped from spec (intentional, surfaced to user)
- `anthropic.api.timeout.seconds` config: dropped. `AnthropicProvider` ctor doesn't take timeout; default `HttpTimeout` is 60s. Surface in commit if user objects.

### Type/method consistency
- `data class GatewayDaemonConfig(...)` — single source in Task 2.2; Tasks 4 and 5 only reference it.
- `class GatewayDaemon(config)` — Task 4 declares; Task 5 calls 2-arg method `start()` and `stop()`.
- `class DefaultAgentRunner(createAgent)` — Task 3 declares; Task 4 instantiates with the createAgent lambda.
- `feishuAdapter` constructed with explicit `coroutineScope = scope` (matches GatewayService which passes `serviceScope`).

### Verification commands that depend on host
- `unzip -p ... MANIFEST.MF` — Unix command. For Windows, plan offers a PowerShell alternative or "any archive tool". If the host is Windows and only bash is available, use `jar tf <jar> | grep MANIFEST` then `cd <extracted>` workaround or accept the user inspecting manually.
