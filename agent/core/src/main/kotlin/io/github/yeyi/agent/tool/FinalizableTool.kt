package io.github.yeyi.agent.tool

import kotlinx.serialization.json.JsonElement

/**
 * 可终结能力接口：工具对「本轮是否可安全终结（不再触发下一轮推理）」的唯一裁决者。
 *
 * 短路控制权完全在本接口：ReActAgent 仅在「本轮全部工具均为 [FinalizableTool] 且
 * finalizable() 全返回 true」时直接进入终局协议（emit Final 空响应收尾），
 * 不做任何执行结果判断——但会把本轮各工具的执行结果与原始入参原样回传给本接口，
 * 由工具自己结合成败裁定：
 * - 默认实现以成败为准：执行成功（!result.isError）即可终结，失败则拒绝终结，
 *   让模型下一轮感知错误回执并重试——避免一次失败静默终结、用户什么都拿不到。
 *
 * 注意：工具不必（也不应）依赖实例状态传递本轮成败——执行结果由 ReActAgent 直接回传，
 * 天然无跨回合/跨 agent 串味，多 agent 共享工具实例安全。
 * 接口默认按成败裁决（成功即终结），仅在裁决规则不同时复写。
 */
public interface FinalizableTool {
    /**
     * 本轮工具执行完成后，是否可安全终结（不再触发下一轮推理）。
     *
     * 实现不得抛异常：本方法跑在工具执行之后的短路裁决点，抛出不会被
     * [io.github.yeyi.agent.tool.ToolRegistry.dispatch] 收成错误回执，而是掀翻本轮。
     * 裁决遇到非法入参等情形，按各自契约降级处理（如产卡工具用「不产卡」表达降级），
     * 不要以异常表达。
     *
     * @param arguments 本轮工具调用的原始入参（模型发出的 tool call arguments），
     * 工具可据此裁决终结与否——如按入参决定是否产卡的产卡工具。
     * @param result 本轮工具的执行结果（execute 返回或异常转换后的错误回执），
     * 工具自行结合其成败裁定，ReActAgent 不代劳判断。
     * @return true 表示可终结，false 拒绝终结、强制模型下一轮感知本轮回执。
     * 默认实现为 `!result.isError`：成功即终结，失败交还模型处理。
     */
    public fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean =
        result.isError.not()
}
