package io.github.yeyi.agent.approval

/**
 * 标记接口：实现此接口的工具在执行前需要用户审批。
 *
 * 用法：
 * ```kotlin
 * class DangerousTool : Tool, ApprovalRequired {
 *     // ...
 * }
 * ```
 */
public interface ApprovalRequired
