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
 * @param uiSink 卡片旁路通道，构造器必传注入：持有者（demo/会话）显式决定卡片流作用域；仅由基类 execute 收口使用，不发卡权下放给子类
 */
public abstract class UiGenTool(private val uiSink: UiSink) : Tool, FinalizableTool {

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
     * 模板接线：业务执行 → 产卡发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * buildCard 返回 null（数据异常/为空）时跳过发射，仅返回回执文本。
     * [getData]/[buildCard] 抛出异常时向上传播（registry 统一转错误回执），
     * [finalizable] 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val result = getData(arguments, context)
        buildCard(result, context)?.let { uiSink.emit(it) }
        return ToolExecutionResult.success(result.toString())
    }
}
