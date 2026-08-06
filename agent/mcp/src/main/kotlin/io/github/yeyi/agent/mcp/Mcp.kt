package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.toolset.Toolset
import kotlin.jvm.Volatile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

/**
 * MCP 能力 — 一个 [Toolset] 形态的远端 MCP 服务。
 *
 * 抽象类继承 [Toolset],内部持有可空委托实例([delegate])。
 * - [add] 始终抛 [UnsupportedOperationException]（MCP 工具由远端动态管理）。
 * - [all] 每次调用都通过 [McpClient] 拉取远端工具列表，对每个 [ToolDef] 调用
 *   [adaptTool] 钩子包装为 [Tool],构建新的 [Toolset] 赋值给 [delegate]。
 *   保证工具定义反映远端 MCP 的最新状态。装饰行为由子类覆写 [adaptTool] 注入。
 * - [dispatch] 委托给 [delegate] 中的 [Tool] 执行。
 * 使用方式:
 * ```kotlin
 * class MyMcp(httpClient: HttpClient) : Mcp {
 *     override val name = "my_service"
 *     override val description = "我的 MCP 服务，提供 xxx 能力"
 *     override val client = McpClient(SseTransport("https://...", httpClient = httpClient))
 * }
 * ```
 */
public abstract class Mcp : Toolset {
    /** MCP 客户端实现，负责与远端或本地 MCP 服务进行协议通信。 */
    public abstract val client: McpClient

    @Volatile
    private var delegate: Toolset? = null

    /**
     * MCP 子工具是动态管理的，调用本方法抛 [UnsupportedOperationException]。
     * 静态子 Tool 集合请直接使用 [io.github.yeyi.agent.toolset.Toolset]。
     */
    final override fun add(tool: Tool) {
        throw UnsupportedOperationException(
            "Mcp '$name' tools are dynamic, managed by the MCP server"
        )
    }

    /** MCP 子工具是动态管理的，调用本方法抛 [UnsupportedOperationException]。 */
    final override fun add(tools: Iterable<Tool>) {
        throw UnsupportedOperationException(
            "Mcp '$name' tools are dynamic, managed by the MCP server"
        )
    }

    /**
     * 每次调用都通过 [McpClient] 重新拉取远端工具列表，对每个 [ToolDef] 调用 [adaptTool]
     * 包装为 [Tool]，构建新的 [Toolset]。
     * 保证工具定义反映远端 MCP 服务的最新状态。
     */
    final override fun all(): List<Tool> = createToolset().also { delegate = it }.all()

    /**
     * 委托给 [delegate] 中的 [Tool] 执行。
     */
    final override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult = delegate?.dispatch(name, arguments, context)
        ?: error("Mcp '$name' not initialized: call all() first to fetch tool schemas")

    /**
     * 钩子方法 —— 把 MCP 协议 [ToolDef] 包装为 agent [Tool]。
     *
     * 默认实现是裸的内部 [McpTool]，不做任何装饰。子类可覆写以注入额外的装饰器
     * (例如压缩 schema、缓存、限流等增强行为),默认实现可通过 `super.adaptTool(...)` 复用。
     * 典型用法:
     * ```kotlin
     * class DecoratedCalculatorMcp : Mcp() {
     *     override val client = ...
     *     override fun adaptTool(client: McpClient, toolDef: ToolDef): Tool =
     *         MyDecorator(super.adaptTool(client, toolDef))
     * }
     * ```
     */
    protected open fun adaptTool(client: McpClient, toolDef: ToolDef): Tool =
        McpTool(client, toolDef)

    /**
     * 拉取远端工具列表并包装为 [Toolset] —— [all] 每次都新建,保证工具定义新鲜度。
     * 失败时抛异常,本方法不更新任何外部状态;调用方负责决定是否重试。
     */
    private fun createToolset(): Toolset {
        val toolset = Toolset(name, description)
        val tools = runBlocking { client.toolsList() }.tools
            .map { toolDef -> adaptTool(client, toolDef) }
        toolset.add(tools)
        return toolset
    }
}
