# LazyTool 模块设计

## 概述

LazyTool 是工具延迟加载的统一抽象，通过 `lazyTools(registry)` 入口对用户提供一致的体验。内部根据 `LazyTool.Level` 分为两种加载模式：

| Level | 描述 | 内部机制 |
|-------|------|---------|
| `SCHEMA` | 延迟参数 schema，激活时返回工具的 schema | `SchemaLazyPlugin` |
| `TOOL` | 延迟整个工具，按描述搜索发现工具后执行 | `ToolSearchPlugin` + `SearchTool` |

两种模式共用 `LazyToolRegistry`，共用一个 `ToolCaller` 代理工具。

## 架构图

```
┌─────────────────────────────────────────────────────┐
│              lazyTools(registry)                     │
│                    统一入口                          │
└─────────────────────┬───────────────────────────────┘
                      │
          ┌───────────┴───────────┐
          │                       │
    Level.SCHEMA              Level.TOOL
          │                       │
          ▼                       ▼
┌─────────────────┐     ┌─────────────────────┐
│ SchemaLazyPlugin│     │  ToolSearchPlugin   │
│  (Capability)   │     │  (AgentPlugin)      │
│                 │     │                     │
│  load_lazy_tool │     │  search_tool        │
│                 │     │  SearchTool         │
│                 │     │                     │
│  tool_caller ───┼─────┼─── tool_caller      │
│     (同一实例)   │     │   (同一实例)         │
└─────────────────┘     └─────────────────────┘
```

## 核心类型

### LazyTool

```kotlin
public interface LazyTool : Capability<Unit, LazyToolContext> {
    public val tool: Tool
    public val level: Level

    public companion object {
        public const val CAPABILITY_TYPE: String = "lazy_tool"
    }

    public enum class Level {
        SCHEMA,  // 延迟参数 schema
        TOOL     // 延迟整个工具，按描述搜索
    }
}

public fun LazyTool(tool: Tool, level: LazyTool.Level = LazyTool.Level.SCHEMA): LazyTool
```

### LazyToolRegistry

```kotlin
public class LazyToolRegistry : CapabilityRegistry<LazyTool, Unit, LazyToolContext>
    by DefaultCapabilityRegistry(LazyTool.CAPABILITY_TYPE)
```

### ToolCaller

代理执行工具，实现 Tool 接口。两个 plugin 共用同一个 ToolCaller 实例：

```kotlin
internal class ToolCaller(private val registry: LazyToolRegistry) : Tool, DelegateTool {
    override val name: String = "tool_caller"

    override val description: String = ""

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "tool_name": {
                    "type": "string",
                    "description": "The name of the target LazyTool (not the internal tool name)"
                },
                "arguments": {
                    "type": "object",
                    "description": "Actual parameters schema of the target tool."
                }
            },
            "required": ["tool_name", "arguments"],
            "additionalProperties": false
        }
        """
    )

    override fun resolveTarget(arguments: JsonElement): DelegateTarget {
        val toolName = arguments.jsonObject["tool_name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing tool_name")
        val toolArgs = arguments.jsonObject["arguments"]
            ?: throw IllegalArgumentException("Missing arguments")
        val lazyTool = registry.all().find { it.name == toolName }
            ?: throw AgentException.ToolNotFound(toolName, registry.all().map { it.name })
        return DelegateTarget(lazyTool.tool, toolArgs)
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val target = resolveTarget(arguments)
        return target.tool.execute(target.arguments, context)
    }
}
```

### lazyTools 入口

```kotlin
public fun AgentBuilder.lazyTools(registry: LazyToolRegistry) {
    val lazyTools = registry.all()
    val schemaLazyTools = lazyTools.filter { it.level == LazyTool.Level.SCHEMA }
    val toolSearchTools = lazyTools.filter { it.level == LazyTool.Level.TOOL }

    val toolCaller = ToolCaller(registry)  // 全量 registry

    // SCHEMA level → 过滤后的 registry
    if (schemaLazyTools.isNotEmpty()) {
        val schemaLazyRegistry = LazyToolRegistry()
        schemaLazyTools.forEach { schemaLazyRegistry.register(it) }
        plugin(SchemaLazyPlugin(schemaLazyRegistry, toolCaller))
    }

    // TOOL level → 过滤后的 registry
    if (toolSearchTools.isNotEmpty()) {
        val toolLevelRegistry = LazyToolRegistry()
        toolSearchTools.forEach { toolLevelRegistry.register(it) }
        plugin(ToolSearchPlugin(toolLevelRegistry, toolCaller))
    }
}
```

### SchemaLazyPlugin

SCHEMA level 使用 CapabilityPlugin 架构：

```kotlin
internal class SchemaLazyPlugin(
    registry: LazyToolRegistry,
    private val toolCaller: ToolCaller,
) : CapabilityPlugin<LazyTool, Unit, LazyToolContext>(registry, true) {

    override fun contextFactory(): LazyToolContextFactory = LazyToolContextFactory()
    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(toolCaller)
}
```

### ToolSearchPlugin

```kotlin
internal class ToolSearchPlugin(
    private val registry: LazyToolRegistry,
    private val toolCaller: ToolCaller,
) : AgentPlugin<Unit> {

    override val id: String = "tool-search"

    override fun configure(block: Unit.() -> Unit) {
        // no-op: this plugin has no config
    }

    override fun install(context: AgentPluginContext) {
        context.registerTool(SearchTool(registry))
        context.registerTool(toolCaller)
    }
}
```

### SearchTool

搜索工具，返回匹配的工具列表供 LLM 选择：

```kotlin
internal class SearchTool(private val registry: LazyToolRegistry) : Tool {
    override val name: String = "search_tool"
    override val description: String = ""
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "指代消解后的明确的、清晰的用户指令"
                }
            },
            "required": ["query"]
        }
        """.trimIndent()
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        // TODO: Use model-based semantic search instead of keyword match
        val query = arguments.jsonObject["query"]?.jsonPrimitive?.content ?: ""
        val results = registry.all().filter {
            it.description.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true)
        }
        val definitions = results.joinToString("\n") { it.tool.toDefinition().toString() }
        val text = "search_tool 返回以下工具（通过 tool_caller 调用）:\n$definitions"
        return ToolExecutionResult.success(text)
    }
}
```

**注意**：两个 plugin 注册的 `tool_caller` 是同一个 ToolCaller 实例，ToolRegistry 允许同对象重复注册，不会冲突。

## 文件清单

| 文件 | 职责 |
|-----|------|
| `LazyTool.kt` | 接口定义，包含 `Level` 枚举和工厂函数 |
| `LazyToolRegistry.kt` | 注册中心，委托 `DefaultCapabilityRegistry` |
| `SchemaLazyPlugin.kt` | SCHEMA level 的 Capability 插件 |
| `ToolCaller.kt` | 代理执行工具，两个 plugin 共用同一实例 |
| `LazyToolContext.kt` | 上下文，空标记类 |
| `LazyToolExtensions.kt` | `lazyTools` DSL 入口 |
| `SearchTool.kt` | TOOL level 的搜索工具，返回匹配的工具列表 |
| `ToolSearchPlugin.kt` | TOOL level 的 AgentPlugin |

## 使用方式

```kotlin
val registry = LazyToolRegistry()

// SCHEMA level：延迟参数 schema
registry.register(LazyTool(WebSearchTool(), LazyTool.Level.SCHEMA))

// TOOL level：延迟整个工具，按描述搜索
registry.register(LazyTool(NewsTool(), LazyTool.Level.TOOL))

agent {
    lazyTools(registry)
}
```

## 调用流程

### SCHEMA level

1. LLM 调用 `load_lazy_tool`，传入 `{"name": "weather"}`
2. `LazyTool.activate()` 返回工具的 schema 信息
3. LLM 调用 `tool_caller`，传入 `{"tool_name": "weather", "arguments": {...}}`
4. `ToolCaller` 执行实际工具

### TOOL level

1. LLM 调用 `search_tool`，传入 `{"query": "指代消解后的用户指令"}`
2. `SearchTool.execute()` 返回匹配的工具列表：
   ```
   search_tool 返回以下工具（通过 tool_caller 调用）:
   {name=weather, description=天气查询, parameters_schema={...}}
   {name=news, description=新闻查询, parameters_schema={...}}
   ```
3. LLM 根据返回结果选择工具，调用 `tool_caller`
4. `ToolCaller` 执行实际工具
