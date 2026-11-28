package io.github.yeyi.agent

import io.github.yeyi.agent.tool.Tool

/**
 * Agent 插件接口。
 *
 * 插件本身持有配置，通过 [install] 安装到 [AgentPluginContext]。
 *
 * 使用方式：
 * ```kotlin
 * // 方式1：内部持有 config，通过 configure block 配置
 * agent {
 *     plugin(MyPlugin()) {
 *         addItem(...)
 *     }
 * }
 *
 * // 方式2：外部已有 config，直接传入（configure block 可省略）
 * agent {
 *     plugin(MyPlugin(externalConfig))
 * }
 * ```
 *
 * 插件只能通过 [context][AgentPluginContext] 添加工具、写入指令说明，或读取当前 hook，
 * 无法访问或修改 Agent 的其他配置。
 *
 * @param C 配置类型
 */
public interface AgentPlugin<C : Any> {
    public val id: String

    /**
     * 配置插件。调用方通过 block 在插件内部 config 上执行配置操作。
     *
     * @param block 配置 block，receiver 为插件内部 config
     */
    public fun configure(block: C.() -> Unit)

    /**
     * 将插件安装到 [AgentPluginContext]。
     *
     * 插件只能通过 [AgentPluginContext.registerTool] 注册工具，
     * 通过 [AgentPluginContext.addInstruction] 写入指令说明，
     * 或通过 [AgentPluginContext.hookPipeline] 读取当前 hook。
     */
    public fun install(context: AgentPluginContext)

    /**
     * 插件安装失败异常。
     */
    public class InstallException(msg: String, cause: Throwable? = null) :
        IllegalStateException(msg, cause)
}

/**
 * 插件上下文 — 插件只能通过此接口操作 Agent。
 *
 * 只暴露 [registerTool]、[addInstruction] 两个写入入口和只读的 [hookPipeline]，
 * 防止插件覆盖 Agent 核心配置。
 */
public interface AgentPluginContext {
    /**
     * Agent 当前挂载的 [AgentHook]。
     *
     * [AgentHook] 是纯回调接口：自身没有 add/注册能力，一个 Agent 只能挂一个实例；
     * 要追加回调只能靠 `agent/hook` 模块的 HookPipeline（[AgentHook] 的可组合实现）
     * 从外层包一层间接完成。所以这里暴露的是**当前那个 hook 对象**而不是注册入口，
     * 注册语义由 HookPipeline 承担，本接口不提供。
     *
     * 取值的时点：插件在 [AgentBuilder.build] 内 install，此时宿主的 [AgentBuilder.hook]
     * 已执行完毕，读到的一定是最终值；宿主没设置时才是默认空实现。
     */
    public val hookPipeline: AgentHook

    /**
     * 向 [io.github.yeyi.agent.tool.ToolRegistry] 注册一个工具。
     */
    public fun registerTool(tool: Tool)

    /**
     * 写入一条指令说明，渲染为 [AgentPersona] 的独立段落。
     *
     * [label] 为 null 时追加匿名段落，多次调用累加；非 null 时为具名段落，
     * 同名段落原位替换内容。label 全局共享：多个插件使用同一 label 时，
     * 后写入者覆盖先到者的段落 —— 冲突由插件作者自行规约命名。
     *
     * 具名段落为插件提供了更新自身指令的入口（如特定情况下刷新 persona 中的提示词）。
     *
     * @param content 指令说明内容
     * @param label 段落标识
     */
    public fun addInstruction(content: String, label: String? = null)
}
