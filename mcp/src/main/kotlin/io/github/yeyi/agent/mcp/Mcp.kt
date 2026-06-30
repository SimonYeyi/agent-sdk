package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.capability.Capability
import kotlinx.serialization.json.JsonElement

/**
 * MCP 能力注册项 —— Agent 视角下一个可被模型发现和调用的 MCP 服务。
 *
 * 本接口实现 [Capability]，复用能力框架的注册与发现流程：
 * - 通过 [activate] 暴露 [toolsList] 的结果，让 [io.github.yeyi.agent.capability.CapabilityAdapter] 把本能力适配为
 *   `load_mcp` 工具供 LLM 调用。
 *
 * 使用方式：
 * ```kotlin
 * class MyMcp(httpClient: HttpClient) : Mcp {
 *     override val name = "my_service"
 *     override val description = "我的 MCP 服务，提供 xxx 能力"
 *     override val client = McpClient(SseTransport("https://...", httpClient = httpClient))
 * }
 * ```
 */
public interface Mcp : Capability<Unit, McpContext> {
    /** MCP 服务的唯一标识，用于模型选择调用哪个 MCP。 */
    public override val name: String

    /** MCP 服务的自然语言描述，告诉模型这个服务能做什么。 */
    public override val description: String

    /** MCP 客户端实现，负责与远端或本地 MCP 服务进行协议通信。 */
    public val client: McpClient

    /**
     * 能力框架调用入口：返回该 MCP 服务的工具列表，供 LLM 发现可用工具。
     *
     * 返回内容是带提示前缀的 `toolsList()` 结果，直接作为工具返回文本暴露给 LLM。
     * 实际的 MCP 工具调用（代理调用）由 [CallMcpTool] 负责，不在本能力管辖范围内。
     */
    public override suspend fun activate(arguments: Unit?, context: McpContext): String {
        val toolsList = client.toolsList().tools.toString()
        return "发现以下可用 MCP 工具：\n$toolsList"
    }

    public companion object {
        /** 能力框架中的路由类别名，生成工具名 `load_mcp`。 */
        public const val CAPABILITY_NAME: String = "mcp"
    }
}
