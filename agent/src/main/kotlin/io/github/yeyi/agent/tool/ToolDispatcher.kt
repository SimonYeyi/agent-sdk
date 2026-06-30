package io.github.yeyi.agent.tool

import kotlinx.serialization.json.JsonElement

/**
 * 工具调度接口，根据工具名称分发调用到对应 [Tool] 实例。
 *
 * SDK 内部默认实现为 [ToolRegistry]；可通过此接口接入自定义工具管理逻辑
 * （如远程工具服务、本地插件机制等）。
 */
public interface ToolDispatcher {

    /**
     * 根据工具名称分发调用。
     *
     * @param name 工具名称，对应 [Tool.name]
     * @param arguments LLM 生成的参数字符串（JSON）
     * @param context 执行时上下文
     * @return 工具执行结果；工具不存在时抛出 [io.github.yeyi.agent.AgentException.ToolNotFound]
     */
    public suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult
}