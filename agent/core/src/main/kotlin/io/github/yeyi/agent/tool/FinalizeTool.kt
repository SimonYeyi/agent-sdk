package io.github.yeyi.agent.tool

/**
 * 执行后必然终结当前轮次的能力接口。
 *
 * 实现本接口的工具（典型：异步派发型工具）一旦执行，ReActAgent 必直接进入终局协议
 * （emit [io.github.yeyi.agent.AgentEvent.Final]）结束当前 run，不再触发下一轮推理；
 * 后续结果由外部异步驱动（如 BossAgent 的 report 流）再次唤醒。
 *
 * 注意：本接口是"必然终结"的强契约（执行即承诺终结），而非"可终结"的弱语义。
 * 调用方应通过 `tool is FinalizeTool` 显式探测能力，而非依赖工具名或执行结果标志。
 */
public interface FinalizeTool
