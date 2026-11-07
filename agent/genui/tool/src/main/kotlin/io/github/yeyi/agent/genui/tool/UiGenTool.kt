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
 * 本基类只承载两类卡片：**信息卡**与**问询卡**。两类卡片都是**非轮次阻塞**的——
 * 卡片发射后本轮照常走完，用户点击卡片动作经 [io.github.yeyi.agent.genui.ir.ActionEvent]
 * 回流，触发的是**新一轮**推理，而不是把当前轮次挂起等用户作答。
 *
 * 需要阻塞等待用户决策的卡片（如审批卡）不在本基类范围内，走
 * `io.github.yeyi.agent.approval.Approvable` + `ApprovalHook` 链路：
 * 那条链路由 hook 在轮次内挂起工具执行、等待审批决策，用户无法用自然语言作答。
 *
 * 本基类不支持阻塞式 UI——动作事件一律回流到新一轮；一旦做成阻塞等待，
 * 用户就只剩下「在卡片按钮上点选」这一条回复路径，失去了用自然语言选择回复的能力。
 *
 * 子类只需实现 [getResult] 业务逻辑，[buildCard] 完成 UI 映射；
 * 发射接线由 final [execute] 统一收口，卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * 可终结能力由基类统一收口：本轮执行结果回传给 [FinalizableTool.finalizable]，
 * 成功时允许终结，产卡失败（[getResult]/[buildCard] 抛出异常，registry 转为错误回执）时
 * 拒绝终结，模型下一轮感知失败并重试；[buildCard] 返回 null（本轮不产卡）是合法降级，
 * 回执为 [getResult] 的结果文本，不视为失败。
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

    /**
     * 业务结果：本轮工具结果的唯一来源，取数结果原样存入 agent 记忆。
     *
     * 返回非 null：回执文本是取数结果的字符串形式，存入记忆，模型后续轮次据此读到具体信息——
     * 这是**信息卡**的形态。
     *
     * 返回 null：回执文本固定为「等待用户回复」，同样存入记忆，模型据此感知回合待用户裁决——
     * 这是**问询卡**的形态。之所以 null 即表示问询卡，是因为信息卡必须先经本方法取到信息才能制卡；
     * 返回 null 表示本工具不需要额外信息，没有结果信息可带的卡片就只能是问询卡：
     * 卡片本身就是与用户交互的全部内容，模型等待的是用户的下一次输入（自然语言或卡片动作回流）。
     *
     * 业务异常一律直接抛出（由框架统一转为错误回执，拒绝终结并让模型感知失败重试），
     * 不要吞掉异常或返回 null 伪装成功。
     *
     * 必须复写以满足本工具的 result 语义——信息卡必须返回数据，
     * 无数据返回（返回 null）即问询卡，禁止依赖隐式默认值。
     */
    protected abstract suspend fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement?

    /**
     * 将本轮结构化数据映射为卡片 IR。
     *
     * 正常情形返回非空卡片；返回 null 表示本轮不产卡。
     *
     * [result] 由 [getResult] 返回后传入，是构造卡片所需业务数据的唯一来源：
     * 需要结果数据构造卡片时，应在 [getResult] 中返回工具结果数据，这里直接使用 [result] 参数即可，
     * 不要在本方法内自行取数（如重新 [getResult]）。
     *
     * @param arguments 本轮 tool 调用的原始入参
     * @param result [getResult] 的取数结果
     * @param context 本轮执行上下文
     */
    protected abstract fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext
    ): CardIr?

    /**
     * 模板接线：业务执行 → 产卡路由发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * 回执语义由 [getResult] 返回值决定，而非卡片有无按钮推断：返回数据（信息卡）
     * 回执为数据文本，模型后续轮次能读到具体信息；返回 null（问询卡，卡片即交互）
     * 回执固定为「等待用户回复」，模型据此感知回合待用户裁决。
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
        val result = getResult(arguments, context)
        val card = buildCard(arguments, result, context)
        if (card != null) {
            val agentId = context.agentContext.agentId
            val sink = checkNotNull(registry.resolve(agentId)) {
                "$name is UiGenTool, must emit a card, but agent [$agentId] has no UiSink registered in UiSinkRegistry. Register a sink for any agent using a card-emitting tool."
            }
            sink.emit(card)
        }
        val content = result?.toString()
            ?: if (card != null) {
                // 无业务结果且产卡：问询卡，卡片即交互，等待用户回复
                "Waiting for user reply"
            } else {
                error(
                    "$name produced no card and no tool result: " +
                            "if buildCard returns null, getResult must return the tool result"
                )
            }
        return ToolExecutionResult.success(content)
    }
}
