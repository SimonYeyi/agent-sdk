package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

/**
 * MCP 能力 — 一个 [Toolset] 形态的远端 MCP 服务。
 *
 * 抽象类继承 [Toolset],内部持有可空委托实例([delegate])。
 * - [add] 始终抛 [UnsupportedOperationException]（MCP 工具由远端动态管理）。
 * - [definitions] 首次调用时通过 [McpClient] 拉取远端工具列表，对每个 [ToolDef] 调用
 *   [createMcpTool] 钩子包装为 [Tool]，最后缓存在内部 [Toolset] 委托中。
 *   装饰行为（如压缩）由子类覆写 [createMcpTool] 注入。
 * - [dispatch] 委托给缓存的 [Toolset] 实例，路由到对应 [Tool] 执行。
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
     * 返回当前 MCP 服务的工具定义列表。
     *
     * 首次调用时通过 [McpClient] 拉取远端工具列表，对每个 [ToolDef] 调用 [createMcpTool]
     * 包装为 [Tool]，最后缓存在内部 [Toolset] 委托中。后续调用直接返回缓存结果。
     */
    final override fun definitions(): JsonElement = ensureInitialized().definitions()

    /**
     * 委托给内部缓存的 [Toolset]，由 [createMcpTool] 返回的 [Tool] 处理执行。
     * 委托未初始化时抛错，调用方需先调用 [definitions] 完成初始化。
     */
    final override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult = delegate?.dispatch(name, arguments, context)
        ?: error("Mcp '$name' not initialized: call definitions() first to fetch tool schemas")

    /**
     * 钩子方法 —— 把 MCP 协议 [ToolDef] 包装为 agent [Tool]。
     *
     * 默认实现是裸的内部 [McpTool]，不做任何装饰。子类可覆写以注入额外的装饰器
     * (例如压缩 schema、缓存、限流等增强行为),默认实现可通过 `super.createMcpTool(...)` 复用。
     * 典型用法:
     * ```kotlin
     * class DecoratedCalculatorMcp : Mcp() {
     *     override val client = ...
     *     override fun createMcpTool(client: McpClient, toolDef: ToolDef): Tool =
     *         MyDecorator(super.createMcpTool(client, toolDef))
     * }
     * ```
     */
    protected open fun createMcpTool(client: McpClient, toolDef: ToolDef): Tool =
        McpTool(client, toolDef)

    /**
     * 懒初始化 [delegate] —— 双重检查锁保证并发场景下只拉取一次工具列表。
     * 委托赋值在 `add` 之后，半初始化失败时 [delegate] 保持 null 以便下次重试。
     */
    private fun ensureInitialized(): Toolset {
        if (delegate == null) {
            synchronized(this) {
                if (delegate == null) {
                    runBlocking {
                        val delegate = Toolset(name, description)
                        val tools = client.toolsList().tools.map { toolDef ->
                            createMcpTool(client, toolDef)
                        }
                        delegate.add(tools)
                        this@Mcp.delegate = delegate
                    }
                }
            }
        }
        return delegate!!
    }
}
