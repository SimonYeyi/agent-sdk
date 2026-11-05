package io.github.yeyi.agent.tool

/**
 * 可终结能力接口：工具对「本轮是否可安全终结（不再触发下一轮推理）」的唯一裁决者。
 *
 * 短路控制权完全在本接口：ReActAgent 仅在「本轮全部工具均为 [FinalizableTool] 且
 * finalizable() 全返回 true」时直接进入终局协议（emit Final 空响应收尾），
 * 不做任何执行结果判断——但会把本轮各工具的执行结果原样回传给本接口，
 * 由工具自己结合成败裁定：
 * - 异步派发型工具（[AsyncTool]）执行后无同步可等待结果、也不会失败，继承默认 true；
 * - 同步工具（如产卡工具）若希望「失败后让模型感知并重试」，可在 [finalizable] 中
 *   结合 'result' 裁定（如 result.isError 时返回 false 拒绝终结）。
 *
 * 注意：工具不必（也不应）依赖实例状态传递本轮成败——执行结果由 ReActAgent 直接回传，
 * 天然无跨回合/跨 agent 串味，多 agent 共享工具实例安全。
 * 接口默认返回 true（实现本接口即声明「默认可终结」），仅在需要拒绝终结时复写。
 */
public interface FinalizableTool {
    /**
     * 本轮工具执行完成后，是否可安全终结（不再触发下一轮推理）。
     *
     * @param result 本轮工具的执行结果（execute 返回或异常转换后的错误回执），
     * 工具自行结合其成败裁定，ReActAgent 不代劳判断。
     * @return true 表示可终结，false 拒绝终结、强制模型下一轮感知本轮回执。
     */
    public fun finalizable(result: ToolExecutionResult): Boolean = true
}
