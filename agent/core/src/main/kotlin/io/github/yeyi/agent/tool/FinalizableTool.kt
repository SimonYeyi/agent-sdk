package io.github.yeyi.agent.tool

/**
 * 可终结能力接口：工具对「本轮是否可安全终结（不再触发下一轮推理）」的唯一裁决者。
 *
 * 短路控制权完全在本接口：ReActAgent 仅在「本轮全部工具均为 [FinalizableTool] 且
 * finalizable() 全返回 true」时直接进入终局协议（emit Final 空响应收尾），
 * 不做任何执行结果判断。工具作者可结合本轮执行状态裁定终结能力：
 * - 异步派发型工具（[AsyncTool]）执行后无同步可等待结果、也不会失败，finalizable() 恒 true；
 * - 同步工具（如产卡工具）若作者希望「失败后让模型感知并重试」，可在 execute 中记录
 *   本轮成败，并在 finalizable() 中结合状态返回（如失败时返回 false 拒绝终结）。
 *
 * 注意：finalizable() 的调用时机在工具执行完成之后，工具 execute 写入的本轮内部状态
 * 对其可见；工具作者通过复写本方法自行结合成败等状态，ReActAgent 不代劳。
 */
public interface FinalizableTool {
    /** 本轮工具执行完成后，是否可安全终结（不再触发下一轮推理）。 */
    public fun finalizable(): Boolean
}
