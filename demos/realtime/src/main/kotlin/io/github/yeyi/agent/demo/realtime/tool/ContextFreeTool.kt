package io.github.yeyi.agent.demo.realtime.tool

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement

/**
 * 无需运行时上下文的工具能力接口（demo 层，避免污染核心）。
 *
 * 部分工具（如获取时间、日期等纯查询类工具）的执行只依赖 [execute] 的参数，
 * 不消费 [ToolExecutionContext]。这类工具实现本接口，额外提供仅接收 arguments
 * 的执行入口，便于脱离 agent 运行时独立执行——例如 S2S 实时协议的工具调用
 * 路径不携带 agent 上下文，可通过适配器复用本类工具。
 *
 * 默认将带上下文的 [execute][Tool.execute] 直接转发到无上下文版本，两者返回值
 * 同构（均为 [ToolExecutionResult]），无上下文版本无法感知 context 但可自行完成
 * 结果包装；需要错误语义的工具可自行覆盖。
 */
public interface ContextFreeTool : Tool {
    /** 执行工具逻辑，仅接收 LLM 生成的参数，无需运行时上下文。 */
    public suspend fun execute(arguments: JsonElement): ToolExecutionResult

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
        execute(arguments)
}
