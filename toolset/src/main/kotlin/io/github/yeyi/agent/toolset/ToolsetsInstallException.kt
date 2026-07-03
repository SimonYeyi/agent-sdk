package io.github.yeyi.agent.toolset

/**
 * 重复尝试向同一 Agent 安装 `load_toolset` / `sub_tool_delegate` 时抛出。
 *
 * 这两个工具是 toolset 框架对外暴露的 discovery/delegation 工具,必须保持
 * single-source-of-truth —— 任何走 toolset 框架的 capability DSL(直接 `toolsets()`
 * 调用,或在其之上封装的更高层 DSL)都会安装同一对工具,同一 Agent 只能由其中一个提供。
 *
 * 异常消息引导用户:直接 `toolsets()` 调用通过 grep `toolsets` 关键字定位,封装型
 * DSL 通过阅读 kdoc 定位(走 toolset 框架的 DSL 会在 kdoc 中提及 `toolsets`)。不引用
 * 具体上层模块,避免 toolset 模块对上层模块形成软依赖。
 */
public class ToolsetsInstallException(
    cause: Throwable? = null,
) : IllegalStateException(
    "load_toolset / sub_tool_delegate is already installed on this Agent. " +
        "These are the toolset discovery/delegation tools, and only one source " +
        "can install them per Agent. The source may be a direct `toolsets()` " +
        "call (grep `toolsets` to find these) or a higher-level DSL that wraps " +
        "the toolset framework (its kdoc will mention `toolsets`). Multiple such " +
        "sources are configured on this Agent; remove all but one.",
    cause,
)
