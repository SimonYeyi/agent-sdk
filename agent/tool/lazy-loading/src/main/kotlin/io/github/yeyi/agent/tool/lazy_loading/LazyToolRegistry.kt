package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry

/**
 * LazyTool 的注册中心，复用 [DefaultCapabilityRegistry] 的逻辑。
 */
public class LazyToolRegistry :
    CapabilityRegistry<LazyTool, Unit, SchemaLazyContext> by DefaultCapabilityRegistry(LazyTool.CAPABILITY_TYPE)
