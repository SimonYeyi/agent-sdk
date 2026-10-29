# mcps 与 toolset 自动合并 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `mcps()` 与 `toolsets()` 可在同一 Agent 上同时使用并自动合并到同一组共享工具（单份 `load_toolset` + `member_tool_delegate`），`McpRegistry` 不再要求注入 `ToolsetRegistry`。

**Architecture:** `AgentBuilder.plugins` 改为 `Map<String, AgentPlugin<*>>` 并新增 `findPlugin(id)` 泛型反查；`toolsets()` 在 toolset 模块内实现「反查已装插件 → 内容并入 / 新建」的合并逻辑；`mcps()` 退化为 `toolsets(registry.toolsetRegistry)` 薄包装；toolset 插件统一委托模式（移除 `enableDelegateAdaptMode`）。

**Tech Stack:** Kotlin 2.x、kotlin.test、kotlinx-coroutines-test、Gradle 多模块（`agent:core` / `agent:toolset` / `agent:mcp` / `agent:config`）。

**规格:** `docs/superpowers/specs/2026-10-27-mcps-toolset-merge-design.md`

## Global Constraints

- 只 commit 不 push（用户全局 git 规则）。
- commit message 格式 `<type>(<module>): <subject>`；多模块路径非主模块取「模块名/最后一段目录名」（如 `agent/toolset/...` → `refactor(toolset)`；跨 core+toolset+mcp 的核心机制变更用 `refactor(core+toolset+mcp)` 风格可参照 git log 既有先例 `fix(core+team)`）。
- `explicitApi()` 模式：public API 必须显式 `public` 修饰符与显式类型。
- `ToolsetPlugin` 保持 `internal`，mcp 模块不得 import 它；合并逻辑只在 toolset 模块内。
- `BossAgentBuilder.mcps` no-op 保持现状，本次不动 team 侧。
- `CapabilityPlugin`/`CapabilityAdapter` 层的 `enableDelegateAdaptMode` 保留（skill/subagent 在用），仅 toolset 移除。
- 测试命令在仓库根执行：`./gradlew :agent:core:test --tests "..."`（Windows bash 下用 `./gradlew`）。

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt` | 插件表改直存插件实例 + `findPlugin` | 修改 |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt` | findPlugin 测试 | 修改 |
| `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetPlugin.kt` | registry 改 `internal val`、移除模式参数 | 修改 |
| `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensions.kt` | 合并逻辑唯一归属地 | 修改 |
| `agent/toolset/src/test/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensionsTest.kt` | 删 OneToOne 用例、新增合并用例 | 修改 |
| `agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpRegistry.kt` | 移除 toolsetRegistry 构造参数 | 修改 |
| `agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpExtensions.kt` | mcps() 薄包装 | 修改 |
| `agent/mcp/src/test/kotlin/io/github/yeyi/agent/mcp/McpTest.kt` | 构造器瘦身、冲突测试改合并测试 | 修改 |
| `agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentScope.kt` | resolveMcps 去参数、删互斥 hack | 修改 |
| `agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentDefinitionExtensions.kt` | spawn 无条件都调 | 修改 |
| `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgentExtensions.kt` | spawnBoss 无条件都调 | 修改 |
| `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/demo/DemoAgentFactory.kt` | McpRegistry 瘦身 | 修改 |
| `agent/config/src/test/kotlin/io/github/yeyi/agent/config/AgentScopeTest.kt` | 互斥断言改合并断言 | 修改 |

---

### Task 1: AgentBuilder 插件表可反查

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt`

**Interfaces:**
- Produces: `public fun findPlugin(id: String): AgentPlugin<*>?` — 后续 Task 2 的 `toolsets()` 依赖它（按 `"toolset"` id 反查）。
- 行为变更：`plugin()` 的 configure 从 build 时提前到注册时执行；`install` 仍延迟到 build；id 去重与 InstallException 包装语义不变。

- [ ] **Step 1: 写失败测试**

在 `AgentBuilderTest.kt` 的 `// --- plugin() tests ---` 区块末尾（`plugin with no configure block` 测试之后）追加：

```kotlin
    @Test
    fun `findPlugin returns registered plugin by id`() {
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) {}
            override fun install(context: AgentPluginContext) {}
        }

        val b = AgentBuilder().apply { plugin(testPlugin) }

        kotlin.test.assertSame(testPlugin, b.findPlugin("test"))
    }

    @Test
    fun `findPlugin returns null for unknown id`() {
        val b = AgentBuilder()
        kotlin.test.assertNull(b.findPlugin("nope"))
    }

    @Test
    fun `configure runs at plugin registration time not build time`() {
        var configured = false
        val testPlugin = object : AgentPlugin<Unit> {
            override val id = "test"
            override fun configure(block: Unit.() -> Unit) { configured = true }
            override fun install(context: AgentPluginContext) {}
        }

        val b = AgentBuilder().apply { plugin(testPlugin) }
        kotlin.test.assertTrue(configured, "configure should have run inside plugin()")
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentBuilderTest"`
Expected: 编译失败 — `findPlugin` 未定义（unresolved reference）。

- [ ] **Step 3: 最小实现**

`AgentBuilder.kt` 中，将：

```kotlin
    private val plugins = mutableMapOf<String, (AgentPluginContext) -> Unit>()
```

改为：

```kotlin
    private val plugins = mutableMapOf<String, AgentPlugin<*>>()
```

`plugin()` 方法改为：

```kotlin
    public fun <C : Any, P : AgentPlugin<C>> plugin(plugin: P, configure: C.() -> Unit = {}) {
        if (plugin.id in plugins) {
            throw AgentPlugin.InstallException(
                "Plugin with id '${plugin.id}' is already installed. Only one plugin of each type is allowed.",
            )
        }
        plugin.configure(configure)
        plugins[plugin.id] = plugin
    }
```

`installPlugins()` 改为：

```kotlin
    private fun installPlugins() {
        val pluginContext = object : AgentPluginContext {
            override fun registerTool(tool: Tool) = toolRegistry.register(tool)
            override fun appendPersona(label: String, content: String) {
                persona.extra(content, label)
            }
        }
        plugins.values.forEach { p ->
            try {
                p.install(pluginContext)
            } catch (e: Throwable) {
                throw AgentPlugin.InstallException("Plugin '${p.id}' installation failed", e)
            }
        }
    }
```

并在 `plugin()` 方法之后新增：

```kotlin
    /** 按 id 反查已注册插件（泛型能力，不感知具体插件）。 */
    public fun findPlugin(id: String): AgentPlugin<*>? = plugins[id]
```

注意：`AgentPlugin<*>` 星投影下 `install` 可调用（不涉及 `C`），编译器天然守住「configure 不可再调」。

同时更新 `plugin()` 的 kdoc：在「所有插件在 [build] 时统一执行，确保 builder 配置完整。」一句改为「configure 在 `plugin()` 注册时立即执行；install 统一在 [build] 时执行。」

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentBuilderTest"`
Expected: PASS（含原有全部 plugin 用例 + 新增 3 例）。

- [ ] **Step 5: 跑全模块测试防回归**

Run: `./gradlew :agent:core:test`
Expected: PASS（现有 4 个 plugin 用例不依赖 configure 延迟执行，已逐一核对）。

- [ ] **Step 6: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt agent/core/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt
git commit -m "refactor(core): AgentBuilder 插件表直存插件实例，新增 findPlugin 反查"
```

---

### Task 2: ToolsetPlugin 暴露 registry + toolsets() 合并逻辑

**Files:**
- Modify: `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetPlugin.kt`
- Modify: `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensions.kt`
- Test: `agent/toolset/src/test/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensionsTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `findPlugin(id: String): AgentPlugin<*>?`。
- Produces: `public fun AgentBuilder.toolsets(registry: ToolsetRegistry)`（签名不变但移除 `enableDelegateAdaptMode` 参数）；`ToolsetPlugin` 的 `internal val registry: ToolsetRegistry`（Task 3 的 mcp 模块**不感知**它，仅本模块使用）。

- [ ] **Step 1: 改测试 — 删除 OneToOne 区块，写合并失败测试**

在 `ToolsetExtensionsTest.kt` 中：

1. 整段删除 `// ---------- OneToOne mode ----------` 区块下的 4 个测试（`OneToOne mode registers toolset_ prefix tools for each toolset`、`OneToOne mode does NOT register load_toolset`、`OneToOne mode still registers member_tool_delegate`、`OneToOne mode toolset_ tool description matches the toolset description`）。

2. 将 `toolsets DSL called twice throws InstallException with guidance` 测试**替换**为：

```kotlin
    @Test
    fun `toolsets called twice merges into one shared plugin`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d1"))
        b.toolsets(registryWith("news" to "d2"))
        b.build().run(AgentQuery.text("hi")).toList()

        val names = llm.recorded.single().tools.map { it.name }
        assertEquals(1, names.count { it == "load_toolset" }, "expected exactly one load_toolset, got: $names")
        assertEquals(1, names.count { it == "member_tool_delegate" }, "expected exactly one member_tool_delegate, got: $names")
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
        assertTrue("news" in loadToolset.description, "merged toolset 'news' should be visible: ${loadToolset.description}")
    }

    @Test
    fun `toolsets called twice with duplicate toolset name throws IllegalArgumentException`() = runTest {
        val b = AgentBuilder().apply { llmProvider(RecordingLlm()) }
        b.toolsets(registryWith("weather" to "d1"))
        assertFailsWith<IllegalArgumentException> {
            b.toolsets(registryWith("weather" to "d2"))
        }
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:toolset:test --tests "io.github.yeyi.agent.toolset.ToolsetExtensionsTest"`
Expected: 编译失败 — `toolsets(registry, enableDelegateAdaptMode = ...)` 参数已不存在（先做 Step 3 的签名删改才会出现此失败；若先跑则在新增用例上编译报 unresolved / too many arguments）。

- [ ] **Step 3: 实现**

`ToolsetPlugin.kt` 全文替换为：

```kotlin
package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * Toolset 的接线模板 —— 统一委托模式（单一 `load_toolset` + 共享 `member_tool_delegate`）。
 *
 * 仅供 Toolset 模块内部 `toolsets(registry)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 *
 * [registry] 为 internal val —— 供本模块的合并逻辑把后续注册表内容并入共享插件。
 */
internal class ToolsetPlugin(
    internal val registry: ToolsetRegistry,
) : CapabilityPlugin<Toolset, Unit, ToolsetContext>(registry, enableDelegateAdaptMode = true) {

    override fun contextFactory(): ToolsetContextFactory = ToolsetContextFactory()

    override fun auxiliaryTools(): List<Tool> = listOf(MemberToolDelegate(registry))
}
```

`ToolsetExtensions.kt` 全文替换为：

```kotlin
package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder

/**
 * DSL — 将 [ToolsetRegistry] 中所有 Toolset 注册到 [AgentBuilder]。
 *
 * 与走 toolset 框架的其他 capability DSL（如 `mcps`）**可共存**：本 DSL 可重复调用,
 * 第二次起把注册表内容并入已安装的共享插件 —— 最终仍只有一份
 * `load_toolset` + `member_tool_delegate`,所有 Toolset 挂在同一个注册表下。
 *
 * Toolset 重名时抛 [IllegalArgumentException]（注册表层 fail-fast）。
 */
public fun AgentBuilder.toolsets(registry: ToolsetRegistry) {
    val existing = findPlugin(Toolset.CAPABILITY_TYPE) as? ToolsetPlugin
    if (existing == null) {
        plugin(ToolsetPlugin(registry))
    } else {
        existing.registry.register(registry.all())
    }
}
```

（`Toolset.CAPABILITY_TYPE` 同包直接限定引用，无需 import。）

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:toolset:test`
Expected: PASS（`ToolsetPluginTest` 中 `ToolsetPlugin(registry)` 构造仍兼容——移除的是带默认值的第二参数，单参调用不受影响）。

- [ ] **Step 5: Commit**

```bash
git add agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetPlugin.kt agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensions.kt agent/toolset/src/test/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensionsTest.kt
git commit -m "refactor(toolset): toolsets DSL 改为共享插件合并语义，统一委托模式"
```

---

### Task 3: McpRegistry 瘦身 + mcps() 薄包装

**Files:**
- Modify: `agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpRegistry.kt`
- Modify: `agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpExtensions.kt`
- Test: `agent/mcp/src/test/kotlin/io/github/yeyi/agent/mcp/McpTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `toolsets(registry: ToolsetRegistry)`（mcp 模块通过既有 `api(project(":agent:toolset"))` 依赖访问此 public DSL）。
- Produces: `public class McpRegistry(clientInfo: ClientInfo)` —— Task 4 的 config 模块按新构造器调用。

- [ ] **Step 1: 改测试**

在 `McpTest.kt` 中：

1. 全文把 `McpRegistry(ToolsetRegistry(), ...)` 构造改为 `McpRegistry(...)`（共约 10 处：`register injects clientInfo...`、`register iterable...`、`registering duplicate Mcp name...`、`unregisterAll closes...`、`mcps DSL installs...`、`mcps DSL load_toolset description...`、`mcps and toolsets together...`、`mcps then toolsets...`）。改完后删除不再使用的 `import io.github.yeyi.agent.toolset.ToolsetRegistry`（若其他测试仍用则保留——`LocalTransportTest.kt:133` 在另一个文件）。

2. 将 `mcps and toolsets together throw InstallException with mcps-specific guidance` 整个测试**替换**为：

```kotlin
    @Test
    fun `mcps and toolsets together merge into one shared toolset plugin`() = runTest {
        val llm = StubLlm()
        val mcpReg = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        val tsReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather-desc").apply { add(stubTool("get_weather")) })
        }

        AgentBuilder().apply {
            llmProvider(llm)
            toolsets(tsReg)
            mcps(mcpReg)
        }.build().run(AgentQuery.text("hi")).toList()

        val names = llm.recorded.single().tools.map { it.name }
        assertEquals(1, names.count { it == "load_toolset" }, "expected exactly one load_toolset, got: $names")
        assertEquals(1, names.count { it == "member_tool_delegate" }, "expected exactly one member_tool_delegate, got: $names")
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description, "merged Mcp 'calc' should be visible: ${loadToolset.description}")
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
    }

    @Test
    fun `mcps then toolsets merges in reverse order`() = runTest {
        val llm = StubLlm()
        val mcpReg = McpRegistry(ClientInfo("", "")).apply {
            register(fakeMcp(name = "calc", description = "calc-desc"))
        }
        val tsReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather-desc").apply { add(stubTool("get_weather")) })
        }

        AgentBuilder().apply {
            llmProvider(llm)
            mcps(mcpReg)
            toolsets(tsReg)
        }.build().run(AgentQuery.text("hi")).toList()

        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("calc" in loadToolset.description, "merged Mcp 'calc' should be visible: ${loadToolset.description}")
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
    }
```

3. 在测试类内补充 `stubTool` 辅助（若类内已有等价物则复用，`StubMemberTool` 是 toolset 模块的，mcp 模块没有）：

```kotlin
    private fun stubTool(name: String): Tool = object : Tool {
        override val name: String = name
        override val description: String = "stub"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(
            arguments: kotlinx.serialization.json.JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionResult = ToolExecutionResult.success("ok")
    }
```

并按需补 import：`io.github.yeyi.agent.toolset.Toolset`、`io.github.yeyi.agent.tool.Tool`、`io.github.yeyi.agent.tool.ToolParameters`、`io.github.yeyi.agent.tool.ToolExecutionContext`、`io.github.yeyi.agent.tool.ToolExecutionResult`、`kotlin.test.assertEquals`（按编译器提示补齐）。

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:mcp:test --tests "io.github.yeyi.agent.mcp.McpTest"`
Expected: 编译失败 — `McpRegistry` 构造器仍要求两个参数（too few arguments）。

- [ ] **Step 3: 实现**

`McpRegistry.kt` 全文替换为：

```kotlin
package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MCP 服务注册表。
 *
 * 内部把 [Mcp] 作为一种特殊的 [io.github.yeyi.agent.toolset.Toolset] 复用 toolset
 * 框架的发现与代理调用能力。本类对外只暴露类型收窄的 [register] / [unregisterAll],
 * MCP 实现细节封装在模块内 —— 调用方不感知 toolset。
 *
 * `register` 时把 [clientInfo] 注入到每个 [Mcp] 实例的 [McpClient];
 * `unregisterAll` 时关闭所有 MCP 连接,然后清空注册表。
 *
 * 与 `toolsets()` DSL 可共存: [mcps][io.github.yeyi.agent.mcp.mcps] 内部把本注册表
 * 并入共享的 toolset 插件,自动合并。
 *
 * @param clientInfo 注入到每个 MCP 客户端的 [ClientInfo]。
 */
public class McpRegistry(private val clientInfo: ClientInfo) {
    internal val toolsetRegistry = ToolsetRegistry()

    /** 注册单个 [Mcp] 服务,自动注入 [clientInfo]。 */
    public fun register(mcp: Mcp) {
        mcp.client.clientInfo = clientInfo
        toolsetRegistry.register(mcp)
    }

    /** 批量注册 [Mcp] 服务。 */
    public fun register(mcps: Iterable<Mcp>) {
        mcps.forEach(::register)
    }

    /** 关闭所有 MCP 连接,然后清空注册表。返回关闭协程的 [Job],调用方可 `join()` 等待。 */
    @OptIn(DelicateCoroutinesApi::class)
    public fun unregisterAll(): Job {
        // 先快照 Mcp 列表,再清空注册表;否则协程跑起来时 all() 已空。
        return toolsetRegistry.all().filterIsInstance<Mcp>().let { mcps ->
            GlobalScope.launch {
                mcps.forEach { runCatching { it.client.close() } }
            }
        }.also {
            toolsetRegistry.unregisterAll()
        }
    }
}
```

`McpExtensions.kt` 的 `mcps()` 函数与 kdoc 替换为：

```kotlin
/**
 * 将 MCP 服务注册表挂载到 Agent Builder。
 *
 * 与 [io.github.yeyi.agent.toolset.toolsets] **可共存** —— 本 DSL 内部把
 * [McpRegistry] 持有的 toolset 注册表并入共享的 toolset 插件,两个 DSL 谁先谁后
 * 都只会安装一份 `load_toolset` + `member_tool_delegate`,普通 Toolset 与 MCP
 * 服务同时挂在其下。
 *
 * 示例:
 * ```kotlin
 * val mcpRegistry = McpRegistry(ClientInfo("my-agent", "1.0")).apply {
 *     register(LiveScoreMcp(httpClient))
 *     register(CalculatorMcp())
 * }
 *
 * val agent = agent {
 *     llmProvider(OpenAiProvider(...))
 *     mcps(mcpRegistry)
 *     toolsets(myToolsetRegistry)   // 可选,自动合并
 * }
 * ```
 */
public fun AgentBuilder.mcps(registry: McpRegistry) {
    toolsets(registry.toolsetRegistry)
}
```

（保留文件内 `internal suspend fun McpClient.toolsList()` 不动；删除原 try/catch 与 `import io.github.yeyi.agent.AgentPlugin`。）

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:mcp:test`
Expected: PASS（含 `LocalTransportTest`——其 `McpRegistry(ToolsetRegistry(), ClientInfo(...))` 构造需同步改为单参：`LocalTransportTest.kt:133`）。

若 `LocalTransportTest` 编译失败，同步修改该行：

```kotlin
val registry = McpRegistry(ClientInfo("test", "1.0")).apply { register(mcp) }
```

- [ ] **Step 5: Commit**

```bash
git add agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpRegistry.kt agent/mcp/src/main/kotlin/io/github/yeyi/agent/mcp/McpExtensions.kt agent/mcp/src/test/kotlin/io/github/yeyi/agent/mcp/McpTest.kt agent/mcp/src/test/kotlin/io/github/yeyi/agent/mcp/LocalTransportTest.kt
git commit -m "refactor(mcp): McpRegistry 移除 toolsetRegistry 注入，mcps 与 toolsets 自动合并"
```

---

### Task 4: config 模块适配 + spawn 无条件双调

**Files:**
- Modify: `agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentScope.kt`
- Modify: `agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentDefinitionExtensions.kt`
- Modify: `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgentExtensions.kt`
- Test: `agent/config/src/test/kotlin/io/github/yeyi/agent/config/AgentScopeTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `McpRegistry(clientInfo: ClientInfo)`；Task 2 的 `toolsets()` 合并语义。
- Produces: 无新接口——`ResolveInfo.toolsetRegistry` 与 `ResolveInfo.mcpRegistry` 可同时非 null。

- [ ] **Step 1: 改测试**

在 `AgentScopeTest.kt` 中，将 `toolsets with mcps share toolset slot and spawn does not conflict` 测试**替换**为：

```kotlin
    @Test
    fun `toolsets with mcps both resolve and spawn merges without conflict`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(
            toolsets = listOf("ts1"),
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(command = "echo")
                )
            )
        )

        val info = scope.resolver().resolve(definition)

        // mcps 与 toolsets 同时非空：两者都解析，spawn 经共享插件自动合并，不再互斥
        assertNotNull(info.toolsetRegistry)
        assertNotNull(info.mcpRegistry)

        val agent = definition.spawn(scope)
        assertNotNull(agent)
    }
```

（该文件已有 `assertNotNull` import 的话直接用；`toolsets = ...` 声明见 `createTestDefinition` 既有参数。）

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:config:test --tests "io.github.yeyi.agent.config.AgentScopeTest"`
Expected: 编译失败 — `resolveMcps(definition, toolsetRegistry)` 仍要求两个参数的调用方签名不匹配，或行为断言失败（`assertNull(info.toolsetRegistry)` 被替换后新断言 `assertNotNull` 失败）。注意：此模块编译依赖 mcp 模块已改，若 Task 3 未完成则先失败在 McpRegistry 构造器。

- [ ] **Step 3: 实现**

`AgentScope.kt` 的 `resolve()` 中，将：

```kotlin
        val mcpRegistry = resolveMcps(definition, toolsetRegistry)
        return ResolveInfo(
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry,
            // 互斥在此层解决：声明 mcps 时普通 Toolset 已随 McpRegistry 的 toolset 槽位一并安装，
            // 此处置 null，spawn 顺序调用 toolsets()/mcps() 时天然只走一路，避免插件二次安装冲突
            toolsetRegistry = if (mcpRegistry != null) null else toolsetRegistry,
```

改为：

```kotlin
        val mcpRegistry = resolveMcps(definition)
        return ResolveInfo(
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry,
            toolsetRegistry = toolsetRegistry,
```

`resolveMcps` 方法改为：

```kotlin
    private fun resolveMcps(definition: AgentDefinition): McpRegistry? {
        if (definition.mcps.isNullOrEmpty()) return null
        val registry = McpRegistry(
            clientInfo = ClientInfo(definition.name, definition.version.toString())
        )
```

（方法体其余部分——`definition.mcps.forEach { config -> ... }` 到 `return registry`——保持不变。）

`AgentDefinitionExtensions.kt` 的 `spawn()` 中，将：

```kotlin
        // toolsets 与 mcps 的互斥已在 AgentResolver.resolve() 中处理：mcps 非空时 toolsetRegistry 置 null，此处调用天然互斥
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
```

改为：

```kotlin
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
```

（`resolveInfo.mcpRegistry?.let { mcps(it) }` 行保持不变——合并机制下两行可共存。）

`team/BossAgentExtensions.kt` 无需代码变更（`toolsets()` / `mcps()` 都已在调用，且 `BossAgentBuilder` 侧 `mcps()` 是 no-op、`toolsets(registry)` 存字段——合并语义不改变该文件行为）。仅当编译器报告 `mcps(it)` 与新签名不匹配时才需要检查（不会——`mcps(registry: McpRegistry)` 签名未变）。

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:config:test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentScope.kt agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentDefinitionExtensions.kt agent/config/src/test/kotlin/io/github/yeyi/agent/config/AgentScopeTest.kt
git commit -m "refactor(config): 移除 mcps 与 toolsets 互斥 hack，spawn 双 DSL 自动合并"
```

---

### Task 5: demos 适配 + 全量回归

**Files:**
- Modify: `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/demo/DemoAgentFactory.kt`

**Interfaces:**
- Consumes: Task 3 的 `McpRegistry(clientInfo: ClientInfo)` 单参构造。

- [ ] **Step 1: 修改 DemoAgentFactory**

将（DemoAgentFactory.kt:100-108）：

```kotlin
        val mcpRegistry =
            McpRegistry(
                ToolsetRegistry(),
                ClientInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
            ).apply {
```

改为：

```kotlin
        val mcpRegistry =
            McpRegistry(
                ClientInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
            ).apply {
```

并删除文件顶部 `import io.github.yeyi.agent.toolset.ToolsetRegistry`（若无其他使用处——grep 确认后再删）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :demos:agent:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 全量回归**

Run: `./gradlew :agent:core:test :agent:capability:test :agent:toolset:test :agent:mcp:test :agent:config:test :agent:skill:test :agent:subagent:test`
Expected: 全部 PASS。重点确认：
- `CapabilityAdapterTest` 的 `enableDelegateAdaptMode=false` 用例仍通过（capability 层参数未动）。
- `SkillExtensionsTest` / `SubagentPluginTest` 的模式参数用例仍通过。
- `McpStdioServerMain` 等 fixture 无构造器引用（grep 确认过仅测试与 demo 引用 McpRegistry）。

- [ ] **Step 4: Commit**

```bash
git add demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/demo/DemoAgentFactory.kt
git commit -m "refactor(demos): DemoAgentFactory 适配 McpRegistry 单参构造"
```

---

## 任务依赖

Task 1 → Task 2 → Task 3 → Task 4 → Task 5（严格顺序；每个 Task 结束时全模块测试绿、可独立 commit）。

## 验收对照（规格 → 任务）

| 规格节 | 任务 |
|---|---|
| 节 1 AgentBuilder 插件表可反查 | Task 1 |
| 节 2 toolset 合并逻辑 + 统一委托模式 | Task 2 |
| 节 3 McpRegistry 瘦身 + mcps 薄包装 | Task 3 |
| 节 4 调用方适配（config/team/demos） | Task 4、Task 5 |
| 节 5 生命周期 kdoc 披露 | Task 3（McpRegistry/mcps kdoc 已含；`SpawnedAgent.shutdown` 行为不变，无需改） |
| 节 6 测试 | 各 Task 内嵌 TDD 步骤 |
