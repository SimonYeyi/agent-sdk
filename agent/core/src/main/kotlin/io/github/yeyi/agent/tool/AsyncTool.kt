package io.github.yeyi.agent.tool

/**
 * 异步执行、结果不阻塞本轮的能力接口。
 *
 * 实现本接口的工具（典型：异步派发型工具，如 publish/cancel）执行后立即返回
 * "已受理 / 执行中"状态，真正的结果由外部异步通道（如 report 流）后续送达。
 * 因此当本轮所有工具调用均满足可终结裁决时，ReActAgent 直接进入终局协议
 * （emit [io.github.yeyi.agent.AgentEvent.Final]）结束当前 run，不再触发下一轮
 * 推理——此时没有可同步等待的下一步结果，过渡语已在调用消息内同步输出。
 *
 * 本接口同时是 [FinalizableTool] 的标记特例：异步派发无同步结果、也不会失败，
 * 继承接口默认的 finalizable() = true（无「执行成功仍需下一轮推理」的情形）。
 *
 * 注意：调用方应通过 `tool is AsyncTool` 显式探测异步能力、通过
 * `tool is FinalizableTool` 探测终结裁决能力，而非依赖工具名或执行结果标志；
 * 终结与否由 ReActAgent 在工具执行后将结果回传 finalizable(result) 交由工具自裁。
 */
public interface AsyncTool : FinalizableTool
