# mcps 与 toolset 自动合并 —— 设计

## 背景与问题

`mcps` 这个 DSL 依赖 toolset 实现：内部就是调 `toolsets(registry.toolsetRegistry)`。二者安装的是同一组**固定名工具**：

- `load_toolset`（`CapabilityLoadTool`，toolset 类别路由工具）
- `member_tool_delegate`（`MemberToolDelegate`，成员 Tool 代理）

`ToolRegistry.register` 遇到重名即抛 `IllegalArgumentException`，所以 `mcps()` 与 `toolsets()` 同用必然冲突。当前用 try/catch 包装成 `InstallException` 强制调用方二选一。

同时 `McpRegistry` 构造器要求调用方注入 `ToolsetRegistry`（其实还想让普通 Toolset 也塞进这个注册表），把「MCP 是 Toolset 的特例」实现细节暴露给调用方。

**目标**：保住 `mcps` DSL；屏蔽它与 toolset 的关系（调用方无需注入 ToolsetRegistry、不感知冲突）；`mcps()` 与 `toolsets()` 可同时使用并自动合并。

## 核心决策

| 决策点 | 结论 |
|---|---|
| 共存语义 | `mcps()` 与 `toolsets()` 可同时调用，**自动合并**到同一组共享工具 |
| 合并机制 | 从 `AgentBuilder.plugins` 按 id 反查已装的 toolset 插件，内容并入其注册表；不重复装插件 |
| `McpRegistry` | 彻底移除 `toolsetRegistry` 构造参数，内部自持私有注册表 |
| 适配模式 | toolset 插件**统一委托模式**，移除 `enableDelegateAdaptMode` 参数 |
| 合并逻辑归属 | 下沉到 toolset 模块；`mcps()` 退化为 `toolsets()` 薄包装 |
| team 侧 | `BossAgentBuilder.mcps` no-op 保持现状，本次不动（见「范围」） |

## 架构分层

```
agent/core（agentbuilder）   plugins: MutableMap<String, AgentPlugin<*>>，可按 id 反查
        │                    只提供泛型能力，不感知 toolset
        ▼
agent/toolset（toolsets DSL）共享 ToolsetPlugin，固定 id = "toolset"（CAPABILITY_TYPE）
        │                     内部持 ToolsetRegistry（internal val），合并逻辑唯一归属地
        │
agent/mcp（mcps DSL）        McpRegistry 内部自持私有 ToolsetRegistry
                             mcps() 退化为 toolsets(registry.toolsetRegistry) 薄包装
```

### 为什么合并逻辑放 toolset 模块

`ToolsetPlugin` 是 toolset 模块的 `internal` 类。若 `mcps()` 直接反查共享 `ToolsetPlugin` 并读其 registry，mcp 模块无法编译（internal 跨模块不可见，也无法 cast 到 `AgentPlugin<*>`）。把合并逻辑整体放 toolset 模块，`findPlugin` + cast 都在模块内完成，可见性自洽；mcp 模块对 `ToolsetPlugin` 零感知，只依赖 `toolsets()` 这个 public DSL。

## 逐节设计

### 节 1 — agent/core：`AgentBuilder` 插件表可直接反查

`plugins` 从 `MutableMap<String, (AgentPluginContext)->Unit>` 改为 `MutableMap<String, AgentPlugin<*>>`，不做包装类：

```kotlin
private val plugins = mutableMapOf<String, AgentPlugin<*>>()

public fun <C : Any, P : AgentPlugin<C>> plugin(plugin: P, configure: C.() -> Unit = {}) {
    if (plugin.id in plugins) {
        throw AgentPlugin.InstallException("Plugin with id '${plugin.id}' is already installed. ...")
    }
    plugin.configure(configure)      // configure 提前到注册时执行
    plugins[plugin.id] = plugin
}

/** 按 id 反查已注册插件（泛型能力，不感知具体插件）。 */
public fun findPlugin(id: String): AgentPlugin<*>? = plugins[id]

private fun installPlugins() {
    val pluginContext = object : AgentPluginContext { ... }
    plugins.values.forEach { p ->
        try {
            p.install(pluginContext) // install 仍延迟到 build
        } catch (e: Throwable) {
            throw AgentPlugin.InstallException("Plugin '${p.id}' installation failed", e)
        }
    }
}
```

**configure 提前执行的安全性**（已核对所有调用方）：现有 configure block 全是 `{ register(...) }` 形式，操作插件自持有的 registry，注册对象在 `plugin()` 调用前已构造完毕，无人依赖「configure 等 build 才执行」。工具生成仍在 `install`（build 时），`CapabilityLoadTool.description` 是 `by lazy`，build 前合入 registry 的条目运行时全部可见。

**不变的部分**：id 去重、`install` 延迟到 build、`InstallException` 包装（只包 install）、`AgentBuilder` 不感知具体插件。附带收益：configure 抛异常从 build 时提前到 `plugin()` 调用处，fail-fast 更早。

### 节 2 — agent/toolset：合并逻辑唯一归属地

`ToolsetPlugin.registry` 从私有构造参数改为 `internal val`；`ToolsetPlugin` 保持 `internal`；构造器与 `toolsets()` 均移除 `enableDelegateAdaptMode`（统一委托模式，恒走 `DelegationAdapter`）：

```kotlin
public fun AgentBuilder.toolsets(registry: ToolsetRegistry) {
    val existing = findPlugin(Toolset.CAPABILITY_TYPE) as? ToolsetPlugin
    if (existing == null) {
        plugin(ToolsetPlugin(registry))
    } else {
        existing.registry.register(registry.all())   // 内容并入，不重复装插件
    }
}
```

- 移除原 try/catch 冲突包装（合并机制下无冲突路径）。
- **语义决策——并入而非换引用**：首个调用的 registry 被插件持有（保留引用语义）；后续调用把内容快照并入。所有现存调用方都在 DSL 调用前完成注册，快照语义无实际影响。

### 节 3 — agent/mcp：退化为薄包装

```kotlin
public fun AgentBuilder.mcps(registry: McpRegistry) = toolsets(registry.toolsetRegistry)

public class McpRegistry(private val clientInfo: ClientInfo) {
    internal val toolsetRegistry = ToolsetRegistry()
    // register / unregisterAll 不变
}
```

- 构造器移除 `toolsetRegistry` 参数（迷惑根源消除）。
- `InstallException` 包装删除；kdoc 改写为「可与 `toolsets()` 共存，自动合并」。

### 节 4 — 调用方适配

- `AgentScope.resolveMcps`：`McpRegistry(ClientInfo(...))`；`resolve()` 删除「mcps 非空则 toolsetRegistry 置 null」的互斥 hack，两者都正常返回。
- `spawn` / `spawnBoss`：`toolsets()` 与 `mcps()` 无条件都调（自动合并）。
- `DemoAgentFactory`、mcp 模块测试：构造器瘦身，去掉 `ToolsetRegistry()` 参数。
- `BossAgentBuilder.mcps` no-op **保持现状**。

### 节 5 — 生命周期披露（kdoc）

`SpawnedAgent.shutdown()` → `mcpRegistry.unregisterAll()` 关闭 MCP 连接、清空 McpRegistry 内部注册表。若共享 registry 是外部 ToolsetRegistry，其中已并入的 Mcp 条目不随 `unregisterAll()` 移除——但 shutdown 后 `run()` 抛 ISE，agent 已死，残留引用无害。写入 kdoc，不加代码。

### 节 6 — 测试

- core：现有 plugin 测试不动；新增 `findPlugin` 命中/未命中。
- toolset：删 `enableDelegateAdaptMode=false` 用例；新增「两次 `toolsets()` 合并 → 单份 `load_toolset` + `member_tool_delegate`，双注册表均可路由」。
- mcp：构造器瘦身；两个 InstallException 测试改写为双向合并测试（`toolsets→mcps`、`mcps→toolsets`）。
- config：`AgentScopeTest` 互斥断言改为「两者都非 null 且 spawn 不冲突」。

## 范围（明确不做）

- `BossAgentBuilder.mcps` 目前是 **no-op**（`@Suppress("UNUSED_PARAMETER")`），`spawnBoss` 传的 McpRegistry 被静默丢弃，BeastAssembler 也不消费 McpRegistry —— **team 场景下 MCP 从未生效**。合并机制天然给了修复路径（`mcps()` 把内容并入 `toolsetRegistry0`，Beast 的 Horse/Ox 走 `toolsets()` 即可用），但 BossAgent 无 `unregisterAll` 调用点，Stdio 子进程生命周期需另接。本次只动 agent 侧，team no-op 保持现状并在此披露；接通留作后续。
- `CapabilityPlugin` / `CapabilityAdapter` 层的 `enableDelegateAdaptMode` 保留（skill / subagent 在用，测试覆盖不受影响），仅 toolset 移除。

## 错误处理

合并时若普通 Toolset 与 MCP 服务**重名**，`DefaultCapabilityRegistry.register` 的 `require(!containsKey(name))` 抛 `IllegalArgumentException` —— 合理 fail-fast，保持不变。
