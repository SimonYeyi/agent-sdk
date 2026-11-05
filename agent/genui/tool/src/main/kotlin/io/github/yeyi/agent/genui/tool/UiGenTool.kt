package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement

/**
 * 声明「本工具产出可交互卡片」的抽象基类，实现 [Tool] 与 [FinalizableTool]。
 *
 * 子类只需实现 [getData] 业务逻辑，[buildCard] 完成 UI 映射；
 * 发射接线由 final [execute] 统一收口，卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * 可终结能力由基类统一收口：本轮执行结果回传给 [FinalizableTool.finalizable]，
 * 成功时允许终结，产卡失败（[getData]/[buildCard] 抛出异常，registry 转为错误回执）时
 * 拒绝终结，模型下一轮感知失败并重试；[buildCard] 返回 null（数据异常/为空）是合法降级，
 * 回执照常返回，不视为失败。
 * finalizable() 为 final 实现且不持有实例状态——成败直接从本轮执行结果读取，
 * 多 agent 共享同一工具实例也不会串味；终结裁决不允许子类复写——产卡工具的本性即
 * 「产卡成功即终结、失败不终结」；需要「产卡后仍需模型复核/续写」语义的工具
 * 不应继承本基类。
 *
 * 卡片路由同样零实例状态：基类不持有具体 [UiSink]，execute 时从
 * [io.github.yeyi.agent.AgentContext.agentId] 解析本 agent 身份标识，经 [UiSinkRegistry.resolve]
 * 取当前 agent 的 sink 发射——多 agent 共享同一工具实例（含 SpawnedAgent 孵化）天然隔离，
 * 不会把卡片发到别的 agent 的 sink。
 *
 * @param registry 卡片路由注册表，构造器必传注入：应用/会话按 agentId 登记各自 [UiSink]，
 *   工具执行时反向解析；仅由基类 execute 收口使用，不发卡权下放给子类
 */
public abstract class UiGenTool(private val registry: UiSinkRegistry) : Tool, FinalizableTool {

    final override fun finalizable(result: ToolExecutionResult): Boolean = !result.isError

    protected abstract fun getData(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement

    /**
     * 将本轮结构化数据映射为卡片 IR。
     *
     * 正常数据必须返回非空卡片；仅当数据不合法/为空时返回 null 表示本轮不产卡，降级为纯文本回执。
     */
    protected abstract fun buildCard(data: JsonElement, context: ToolExecutionContext): CardIr?

    /**
     * 模板接线：业务执行 → 产卡路由发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * [buildCard] 返回 null（数据异常/为空）是合法降级，本轮不产卡仅返回回执文本；
     * 但 [buildCard] 返回非空卡片时，当前 agent 必须在 registry 中已登记 sink——未登记属装配配置
     * 错误（产卡工具要求使用方先为 agent 登记 sink），直接抛 [IllegalStateException] fail-fast
     * 暴露，绝不静默丢弃卡片；异常向上传播由 registry 统一转错误回执，
     * [finalizable] 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val result = getData(arguments, context)
        val card = buildCard(result, context)
        if (card != null) {
            val agentId = context.agentContext.agentId
            val sink = checkNotNull(registry.resolve(agentId)) {
                "$name is UiGenTool, must emit a card, but agent [$agentId] has no UiSink registered in UiSinkRegistry. Register a sink for any agent using a card-emitting tool."
            }
            sink.emit(card)
        }
        return ToolExecutionResult.success(result.toString())
    }
}
