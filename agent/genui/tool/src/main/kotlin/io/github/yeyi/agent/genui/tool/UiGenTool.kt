package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement

/**
 * 声明「本工具产出可交互卡片」的抽象基类，实现 [Tool]。
 *
 * 子类只需实现 [getData] 业务逻辑，[buildCard] 完成 UI 映射；
 * 发射接线由 final [execute] 统一收口，卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * @param uiSink 卡片旁路通道，构造器必传注入：持有者（demo/会话）显式决定卡片流作用域；仅由基类 execute 收口使用，不发卡权下放给子类
 */
public abstract class UiGenTool(private val uiSink: UiSink) : Tool {

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
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val result = getData(arguments, context)
        buildCard(result, context)?.let { uiSink.emit(it) }
        return ToolExecutionResult.success(result.toString())
    }
}
