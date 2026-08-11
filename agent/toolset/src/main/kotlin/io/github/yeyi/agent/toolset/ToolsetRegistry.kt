package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry

/**
 * 工具集注册中心 — 管理多个 [Toolset]。
 *
 * 复用能力框架生成 `load_toolset`（委托模式）或 `toolset_<name>`（一一映射模式），
 * 配合 [SubToolDelegate] 做子 Tool 代理调用。
 *
 * 用法：
 * ```kotlin
 * val registry = ToolsetRegistry().apply {
 *     register(Toolset("weather", "天气工具集").apply { add(GetWeatherTool()) })
 *     register(Toolset("search", "搜索工具集").apply { add(WebSearchTool()) })
 * }
 *
 * val agent = agent {
 *     llmProvider(...)
 *     toolsets(registry)
 * }
 * ```
 */
public class ToolsetRegistry :
    CapabilityRegistry<Toolset, Unit, ToolsetContext> by DefaultCapabilityRegistry(
        Toolset.CAPABILITY_TYPE
    )
