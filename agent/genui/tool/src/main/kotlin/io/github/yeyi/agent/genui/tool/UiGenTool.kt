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
 * 本基类只承载三类卡片：**不产卡**、**信息卡**与**问询卡**。两类真卡片都是**非轮次阻塞**的——
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
 * 子类必须复写两处：[cardKind] 声明本轮卡片类型、[buildCard] 完成 UI 映射；
 * [getResult] 取业务数据，问询卡可不复写（默认无数据可回执），其余类型必须返回数据；
 * 发射接线由 final [execute] 统一收口，
 * 卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * 可终结能力由基类统一收口，裁决只看本轮卡片类型（[cardKind]）与执行成败：
 * - 问询卡（[CardKind.ASK]）：卡片即向用户的提问，本轮到此为止——成功即终结，等用户作答；
 * - 信息卡（[CardKind.INFO]）：本轮不终结，模型看完回执自行决定继续调工具还是收尾——
 *   「本轮是否到此为止」只有模型看到结果之后才知道，工具无权替它判定（否则模型把工具
 *   当中间步骤调用时会被提前截断，比如先查时间再查天气）；
 * - 不产卡（[CardKind.NONE]）：本轮就是普通取数工具，回执即数据，永不终结；
 * 产卡失败（[getResult]/[buildCard] 抛出异常）一律拒绝终结，
 * 模型下一轮感知失败并重试。
 * 裁决不持有实例状态——卡片类型由入参判定、成败直接取自本轮执行结果。
 *
 * 卡片通道在构造期定死：产卡工具随 agent 构造，持有自己的 [UiSink]，执行时直接发射，
 * 不做运行期解析。子 agent 继承父 agent 的工具实例（连同 sink 绑定），卡片落父通道——
 * 与它继承父的 llmProvider、memory 同属一种继承。
 * 产卡工具实例因此是 agent 私有的：不要在工具里持有跨 agent 的状态（缓存、限流计数）。
 *
 * @param sink 本 agent 的卡片通道，构造器必传注入；仅由基类 execute 收口使用，不发卡权下放给子类
 */
public abstract class UiGenTool(private val sink: UiSink) : Tool, FinalizableTool {

    private companion object {
        /** 回执里告知模型「卡片已展示给用户」，避免它把同样内容在正文里再讲一遍。 */
        const val CARD_SHOWN_NOTE = "(this has already been shown to the user as a ui-card)"
    }

    /**
     * 卡片类型：决定本轮是否产卡、是否可终结，以及回执文本的形态。
     *
     * [NONE] 不产卡、[INFO] 信息卡、[ASK] 问询卡都是**非轮次阻塞**的——
     * 卡片发射后本轮按正常协议走完（问询卡是正常终结，不是挂起等事件），
     * 用户点击卡片动作经 [io.github.yeyi.agent.genui.ir.ActionEvent]
     * 回流，触发的是新一轮推理。
     * 区别在「本轮产不产卡」与「本轮何时结束」：
     * - [NONE] 不产卡：本轮就是普通取数工具，只回执数据，不发卡；
     * - [INFO] 信息卡：携带查询结果，本轮不终结，模型看完回执自行决定继续调工具还是收尾；
     * - [ASK] 问询卡：卡片本身就是向用户的提问，本轮终结，等用户作答。
     */
    protected enum class CardKind {
        /**
         * 不产卡：本轮只取数回执，不发卡。
         *
         * 供「按入参决定本轮要不要卡片」的工具使用：入参自足时走 NONE（普通取数），
         * 入参不足、需要用户裁决时走 ASK（问询卡）。
         * 本类型下基类**不调用** [buildCard]，子类实现返回 null 即可。
         */
        NONE,

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
     * 此时回执兜底为「等待用户回复」；其余类型走到这里属声明与实现不符，execute 直接抛异常暴露。
     *
     * 业务异常一律直接抛出（由框架统一转为错误回执，拒绝终结并让模型感知失败重试），
     * 不要吞掉异常或返回 null 伪装成功。
     */
    protected open suspend fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement? = null

    /**
     * 本轮卡片类型声明：决定本轮是否产卡、是否可终结，以及回执文本的形态。
     *
     * [CardKind.NONE]——本轮不产卡，只取数回执（普通取数工具）。
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
     *
     * 实现必须是入参的全函数、不得抛异常：入参不合法/信息不足等情形用 [CardKind.NONE]
     * 表达（不产卡），非法入参由 [getResult] 抛业务异常走错误回执。
     * 抛异常会让裁决点掀翻本轮——[finalizable] 里 `!result.isError` 先短路，
     * 执行失败时不会二次求值本方法，故只有执行成功后的不一致才会暴露成回合失败。
     */
    protected abstract fun cardKind(arguments: JsonElement): CardKind

    /**
     * 将本轮结构化数据映射为卡片 IR。
     *
     * 正常情形返回非空卡片；返回 null 表示本轮不产卡。
     *
     * [CardKind.NONE] 下基类不调用本方法，实现返回 null 即可。
     *
     * [CardKind.ASK] 下必须返回非空卡片：问询卡是「卡片即问询」，
     * 没有卡片就没有提问，此时若仍终结本轮，用户既看不到卡片也等不到回答——
     * 故基类把「问询卡未产出」判为失败（错误回执，拒绝终结，模型下一轮感知并重试）。
     *
     * [CardKind.INFO] 下返回 null 是合法降级：本轮不产卡，只回执数据（不追加展示告知）。
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
     * 问询卡成功即终结（等用户作答），信息卡与不产卡一律不终结
     * （模型看完回执自行决定下一步），执行失败一律不终结（模型下一轮感知失败并重试）。
     */
    final override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean =
        !result.isError && cardKind(arguments) == CardKind.ASK

    /**
     * 模板接线：业务执行 → 回执定稿 → 产卡路由发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * 回执语义由 [getResult] 返回值与 [cardKind] 共同决定：有数据即回执数据文本，
     * 模型后续轮次据此读到具体信息；无数据且为问询卡（卡片已发射）即回执「等待用户回复」；
     * 无数据且非问询卡属声明与实现不符，直接抛异常。
     * 本轮产了卡时，回执末尾追加「卡片已展示给用户」的告知，避免模型在正文里
     * 把卡片内容再讲一遍；未产卡不追加，避免谎报。
     *
     * 回执定稿在发卡之前：回执不成立（无数据、问询卡未产出）时本轮根本不该把卡发到用户手上——
     * 否则用户看到卡片，模型却收到失败回执去重试，卡片会被重复发出。
     *
     * [buildCard] 返回非空卡片时，发射通道在构造期已定死；通道上没有订阅者属装配配置
     * 错误（产卡工具要求使用方为该 agent 备好 sink 并保持订阅），直接抛 [IllegalStateException]
     * fail-fast 暴露，绝不静默丢弃卡片；异常向上传播由工具执行链路统一转错误回执，
     * [finalizable] 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val kind = cardKind(arguments)
        val result = getResult(arguments, context)
        // 不产卡类型不调用 buildCard：卡片有无由 cardKind 声明，不靠 buildCard 返回 null 协商
        val card = if (kind == CardKind.NONE) null else buildCard(arguments, result, context)
        // 问询卡必须产出：卡片即问询，没卡就没提问，此时终结本轮会让用户干等
        if (kind == CardKind.ASK && card == null) {
            error(
                "$name is an ask card tool but produced no card: " +
                        "an ask card tool must build a card in buildCard"
            )
        }
        val content = result?.toString()
            ?: when (kind) {
                // 问询卡无业务数据可回执：卡片即问询，等待用户回复
                CardKind.ASK -> "Waiting for user reply"
                CardKind.NONE -> error(
                    "$name produced no tool result: a tool that produces no card must return data from getResult"
                )
                CardKind.INFO -> error(
                    "$name produced no tool result: an info card tool must return data from getResult"
                )
            }
        if (card != null) {
            sink.emit(card)
        }
        // 产了卡就要告知模型卡片已送达，避免它把同样的内容在正文里再讲一遍；
        // 未产卡不追加，避免谎报
        val receipt = if (card != null) "$content $CARD_SHOWN_NOTE" else content
        return ToolExecutionResult.success(receipt)
    }
}
