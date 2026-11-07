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
 * 卡片发射后本轮按正常协议走完（问询卡是正常终结，不是挂起等事件），
 * 用户点击卡片动作经 [io.github.yeyi.agent.genui.ir.ActionEvent]
 * 回流，触发的是**新一轮**推理，而不是把当前轮次挂起等用户作答。
 *
 * 需要阻塞等待用户决策的卡片（如审批卡）不在本基类范围内，走
 * `io.github.yeyi.agent.approval.Approvable` + `ApprovalHook` 链路：
 * 那条链路由 hook 在轮次内挂起工具执行、等待审批决策，用户无法用自然语言作答。
 *
 * 本基类不支持阻塞式 UI——动作事件一律回流到新一轮；一旦做成阻塞等待，
 * 用户就只剩下「在卡片按钮上点选」这一条回复路径，失去了用自然语言选择回复的能力。
 *
 * 子类必须复写两处：[cardKind] 声明本工具的卡片类型、[buildCard] 完成 UI 映射；
 * [getResult] 取业务数据，问询卡可不复写（默认无数据可回执），信息卡必须返回数据；
 * 发射接线由 final [execute] 统一收口，
 * 卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * 可终结能力由基类统一收口，裁决只看本轮卡片类型（[cardKind]）与执行成败：
 * - 问询卡（[CardKind.ASK]）：卡片即向用户的提问，本轮到此为止——成功即终结，等用户作答；
 * - 信息卡（[CardKind.INFO]）：本轮不终结，模型看完回执自行决定继续调工具还是收尾——
 *   「本轮是否到此为止」只有模型看到结果之后才知道，工具无权替它判定（否则模型把工具
 *   当中间步骤调用时会被提前截断，比如先查时间再查天气）；
 * 产卡失败（[getResult]/[buildCard] 抛出异常，registry 转为错误回执）一律拒绝终结，
 * 模型下一轮感知失败并重试。
 * 裁决不持有实例状态——卡片类型由入参判定、成败直接取自本轮执行结果，
 * 多 agent 共享同一工具实例也不会串味。
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

    private companion object {
        /** 回执里告知模型「卡片已展示给用户」，避免它把同样内容在正文里再讲一遍。 */
        const val CARD_SHOWN_NOTE = "(this has already been shown to the user as a ui-card)"
    }

    /**
     * 卡片类型：决定本轮是否可终结，以及回执文本的形态。
     *
     * 两种卡片都是**非轮次阻塞**的——卡片发射后本轮按正常协议走完（问询卡是正常终结，
     * 不是挂起等事件），用户点击卡片动作经 [io.github.yeyi.agent.genui.ir.ActionEvent]
     * 回流，触发的是新一轮推理。
     * 区别只在「本轮何时结束」：
     * - [INFO] 信息卡：携带查询结果，本轮不终结，模型看完回执自行决定继续调工具还是收尾；
     * - [ASK] 问询卡：卡片本身就是向用户的提问，本轮终结，等用户作答。
     */
    protected enum class CardKind {
        /** 信息卡：回执为查询结果数据，模型据此继续推理或收尾。 */
        INFO,

        /** 问询卡：卡片即问询，回执为「等待用户回复」，本轮终结等用户作答。 */
        ASK,
    }

    /**
     * 业务结果：本轮工具结果的唯一来源，取数结果原样存入 agent 记忆。
     *
     * 返回非 null：回执文本是取数结果的字符串形式，存入记忆，模型后续轮次据此读到具体信息。
     *
     * 不复写（默认 null）：本轮没有数据可回执——问询卡（[cardKind] 为 [CardKind.ASK]）适用，
     * 此时回执兜底为「等待用户回复」；信息卡走到这里属声明与实现不符，execute 直接抛异常暴露。
     *
     * 业务异常一律直接抛出（由框架统一转为错误回执，拒绝终结并让模型感知失败重试），
     * 不要吞掉异常或返回 null 伪装成功。
     */
    protected open suspend fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement? = null

    /**
     * 本轮卡片类型声明：决定本轮是否可终结，以及回执文本的形态。
     *
     * [CardKind.INFO]——取数型工具：卡片展示查询结果，本轮不终结，
     * 模型看完回执自行决定继续调工具还是收尾。
     *
     * [CardKind.ASK]——问询型工具：卡片本身就是向用户的提问（如「你想查哪个城市」），
     * 本轮到此为止、终结等用户作答。
     *
     * 实现只允许依赖入参：裁决发生在执行之后（[finalizable] 由 ReActAgent 在工具执行完毕后回传），
     * 那时拿不到 [getResult] 的原始返回值；只看入参才能在执行与裁决两处稳定复算同一结论，
     * 且不依赖实例状态（多 agent 共享同一工具实例安全）。
     */
    protected abstract fun cardKind(arguments: JsonElement): CardKind

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
     * 可终结裁决：由卡片类型与执行成败共同决定。
     *
     * 问询卡成功即终结（等用户作答），信息卡一律不终结（模型看完回执自行决定下一步），
     * 执行失败一律不终结（模型下一轮感知失败并重试）。
     */
    final override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean =
        !result.isError && cardKind(arguments) == CardKind.ASK

    /**
     * 模板接线：业务执行 → 产卡路由发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * 回执语义由 [getResult] 返回值与 [cardKind] 共同决定：有数据即回执数据文本，
     * 模型后续轮次据此读到具体信息；无数据且为问询卡（卡片已发射）即回执「等待用户回复」；
     * 无数据且非问询卡属声明与实现不符，直接抛异常。
     * 本轮产了卡时，回执末尾追加「卡片已展示给用户」的告知，避免模型在正文里
     * 把卡片内容再讲一遍；未产卡（[buildCard] 返回 null 的合法降级）不追加，避免谎报。
     * [buildCard] 返回 null（本轮不产卡）是合法降级，本轮不产卡仅返回回执文本；
     * 但 [buildCard] 返回非空卡片时，当前 agent 必须在 registry 中已登记 sink——未登记属装配配置
     * 错误（产卡工具要求使用方先为 agent 登记 sink），直接抛 [IllegalStateException] fail-fast
     * 暴露，绝不静默丢弃卡片；异常向上传播由 registry 统一转错误回执，
     * [finalizable] 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val kind = cardKind(arguments)
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
            ?: if (kind == CardKind.ASK && card != null) {
                // 问询卡无业务数据可回执：卡片即问询，等待用户回复
                "Waiting for user reply"
            } else {
                error(
                    "$name produced no tool result: an info card tool must return data from getResult, " +
                            "only an ask card tool may return null"
                )
            }
        // 产了卡就要告知模型卡片已送达，避免它把同样的内容在正文里再讲一遍；
        // 未产卡（buildCard 返回 null 的合法降级）不追加，避免谎报
        val receipt = if (card != null) "$content $CARD_SHOWN_NOTE" else content
        return ToolExecutionResult.success(receipt)
    }
}
