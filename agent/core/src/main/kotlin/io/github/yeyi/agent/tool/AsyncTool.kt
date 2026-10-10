package io.github.yeyi.agent.tool

/**
 * 异步执行、结果不阻塞本轮的能力接口。
 *
 * 实现本接口的工具（典型：异步派发型工具，如 publish/cancel）执行后立即返回
 * "已受理 / 执行中"状态，真正的结果由外部异步通道（如 report 流）后续送达。
 * 因此当本轮所有工具调用均为 [AsyncTool] 时，ReActAgent 直接进入终局协议
 * （emit [io.github.yeyi.agent.AgentEvent.Final]）结束当前 run，不再触发下一轮
 * 推理——此时没有可同步等待的下一步结果，过渡语已在调用消息内同步输出。
 *
 * 注意：本接口声明的是"异步"能力而非"终结"承诺；是否终结本轮由 ReActAgent
 * 基于"本轮无同步可等待的结果"推导，而非工具自身决定。
 * 调用方应通过 `tool is AsyncTool` 显式探测能力，而非依赖工具名或执行结果标志。
 */
public interface AsyncTool
