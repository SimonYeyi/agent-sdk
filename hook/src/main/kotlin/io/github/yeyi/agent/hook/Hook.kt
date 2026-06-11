package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentHook

/**
 * 标记接口:这个 hook 是 `:hook` 模块"全局 hook 系统"的一部分。
 *
 * 当前 [Hook] 等价于 [AgentHook](继承自它),但保留了独立的命名空间,使得:
 * - 未来引入 `SessionHook`、`LlmCallHook` 等其他 hook 类型时,可以表达"同时实现多种 hook 角色":
 *   `interface FullHook : AgentHook, SessionHook`
 * - DSL 扩展 (`hook(h: Hook)`) 的入参类型可以表达"应当参与 hook 组合/排序"的设计意图
 * - 调用方代码与具体角色解耦:`hook(LoggingHook())`、`hook(MyMetricsHook())` 都通过同一个扩展
 *
 * 实现要求:
 * - 一个类实现 [Hook] 即表示愿意参与 [CompositeHook] 组合
 * - 实现者无需在本接口新增任何方法;[AgentHook] 的方法集就是 hook 回调的完整契约
 */
public interface Hook : AgentHook