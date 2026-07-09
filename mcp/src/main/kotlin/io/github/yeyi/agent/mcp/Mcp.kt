package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.compression.CompressTool
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

/**
 * MCP 能力 — 一个 [Toolset] 形态的远端 MCP 服务。
 *
 * 抽象类继承 [Toolset],内部持有可空委托实例([delegate])。
 * - [add] 始终抛 [UnsupportedOperationException]（MCP 工具由远端动态管理）。
 * - [definitions] 首次调用时通过 [McpClient] 拉取远端工具列表，创建 [Toolset] 委托并缓存。
 *   每个 [McpTool] 会尝试用 [CompressTool] 装饰（可选依赖，通过 `compileOnly` 引入；
 *   若运行时不存在则降级为裸 [McpTool]）。
 * - [dispatch] 委托给缓存的 [Toolset] 实例，路由到对应的 [McpTool] 执行。
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

    private companion object {
        private val compressToolAvailable =
            runCatching { CompressTool::class; true }.getOrDefault(false)
    }

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
     * 首次调用时通过 [McpClient] 拉取远端工具列表，创建 [McpTool] 并用 [CompressTool]
     * 装饰（若可选依赖存在），最后缓存在内部 [Toolset] 委托中。
     * 后续调用直接返回缓存结果。
     */
    final override fun definitions(): JsonElement {
        ensureInitialized()
        return delegate!!.definitions()
    }

    /**
     * 委托给内部缓存的 [Toolset]，由 [CompressTool]（若存在）/ [McpTool] 处理执行。
     * 若委托尚未初始化，先完成初始化。
     */
    final override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        ensureInitialized()
        return delegate!!.dispatch(name, arguments, context)
    }

    /**
     * 懒初始化 [delegate] —— 双重检查锁保证并发场景下只拉取一次工具列表。
     * 委托赋值在 `add` 之后，半初始化失败时 [delegate] 保持 null 以便下次重试。
     */
    private fun ensureInitialized() {
        if (delegate == null) {
            synchronized(this) {
                if (delegate == null) {
                    runBlocking {
                        val delegate = Toolset(name, description)
                        val tools = client.toolsList().tools.map { toolDef ->
                            val mcpTool = McpTool(client, toolDef)
                            if (compressToolAvailable) CompressTool(mcpTool) else mcpTool
                        }
                        delegate.add(tools)
                        this@Mcp.delegate = delegate
                    }
                }
            }
        }
    }
}
