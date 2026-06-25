package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement

/**
 * MCP 能力注册项 —— Agent 视角下一个可被模型发现和调用的 MCP 服务。
 *
 * 这是 MCP 模块与 Agent 能力框架对齐的顶层接口，定位类似 [io.github.yeyi.agent.skill.Skill]
 * 或子 agent 能力：提供 [name] 和 [description] 供模型识别，内部通过 [client] 执行实际的
 * MCP 协议调用。
 *
 * 模型通过 `load_mcp` 工具看到所有已注册的 MCP 名称和描述，再通过 `call_mcp` 工具
 * 调用具体 MCP 服务上的工具。
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
public interface Mcp {
    /** MCP 服务的唯一标识，用于模型选择调用哪个 MCP。 */
    public val name: String

    /** MCP 服务的自然语言描述，告诉模型这个服务能做什么。 */
    public val description: String

    /** MCP 客户端实现，负责与远端或本地 MCP 服务进行协议通信。 */
    public val client: McpClient

    /**
     * 获取该 MCP 服务下所有可用工具列表，返回 JSON 字符串。
     *
     * 返回格式为 MCP 协议 `tools/list` 的 tools 数组 JSON 字符串，
     * 每个元素是一个工具定义对象，包含 name、description、inputSchema 等字段。
     */
    public suspend fun toolsList(): String =  client.toolsList().tools.toString()

    /**
     * 调用该 MCP 服务上的指定工具，返回结果 JSON 字符串。
     *
     * [params] 为 MCP 协议 `tools/call` 的参数对象，包含 `name`（工具名）和
     * `arguments`（工具入参）。返回值为工具执行结果的 JSON 字符串。
     *
     * @throws McpException 工具执行失败或 MCP 服务返回错误时抛出
     */
    public suspend fun toolsCall(params: JsonElement): String = client.callTool(params).toString()

    /** 关闭该 MCP 服务连接，释放资源。 */
    public suspend fun close(): Unit = client.close()
}