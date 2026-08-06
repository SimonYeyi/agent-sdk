package io.github.yeyi.agent.tool

/**
 * 重复注册同名 [Tool] 时抛出。
 *
 * 发生在 Agent 构建期（`AgentBuilder.tool(...)` / `ToolRegistry.register(...)`），
 * 尚未进入 ReAct 循环，因此**不**纳入 [io.github.yeyi.agent.AgentException] 体系——
 * 那是运行期领域异常的根类型。
 */
public class ToolDuplicateException(
    public val toolName: String,
    public val existingNames: Collection<String>,
) : IllegalStateException("Duplicate tool name: '$toolName'. Existing: $existingNames")
