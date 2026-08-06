package io.github.yeyi.agent.tool

import kotlinx.serialization.json.JsonElement

/**
 * 工具接口，Agent 通过它调用外部能力（搜索、数据库、API 等）。
 *
 * 实现者需提供：
 * - 工具名称（唯一标识）
 * - 人类可读的描述（供 LLM 理解何时调用）
 * - 参数 schema（JSON Schema 格式，供 LLM 生成参数）
 * - [execute] 实现（实际执行业务逻辑）
 *
 * [execute] 应遵循以下约定：
 * - 响应 coroutine 取消检查（工具内部 suspend 操作自动响应取消）
 * - 资源清理应在 finally / use {} 中完成
 * - 业务异常应返回 [ToolExecutionResult.isError]=true，而非抛出
 */
public interface Tool {
    /** 工具唯一名称，LLM 通过此名称选择调用。 */
    public val name: String

    /** 人类可读描述，说明工具用途及适用场景，供 LLM 理解何时使用。 */
    public val description: String

    /** 参数 JSON Schema，LLM 根据此 schema 生成调用参数。空参数时用 [ToolParameters.Empty]。 */
    public val parametersSchema: ToolParameters

    /**
     * 执行工具逻辑。
     *
     * @param arguments LLM 生成的参数（JSON 结构）
     * @param context 执行时上下文，含 callId 和 agent 运行时信息
     * @return 执行结果；业务异常应返回 [ToolExecutionResult.isError]=true，而非抛出
     */
    public suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult
}
