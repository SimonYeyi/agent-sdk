package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 声明「本工具产出可交互卡片」的抽象基类，实现 [Tool] 与 [FinalizableTool]。
 *
 * 分两类：
 * - **按需出卡**（默认，[alwaysShowUi] = false）：本轮出不出卡、结不终结，由模型逐次
 *   传入的框架参数 show_ui 决定——show_ui=true 出卡并终结本轮（卡片已经交到用户手上，
 *   本轮到此为止，等用户回复或点选卡片动作）；show_ui=false 不出卡、不终结——本轮只是
 *   推理的中间步骤，模型看到回执后自行决定下一步。
 * - **必然出卡**（[alwaysShowUi] = true）：工具本身就是「出卡」这件事，承载的内容
 *   非得给用户看不可（如把结果现场排成一张卡）。此时不给模型 show_ui 参数——不必问，
 *   每次调用都出卡并终结本轮。
 *
 * 按需出卡时，show_ui 在 schema 里声明为必传：模型每次调用都得表态出不出卡；
 * 真缺省时（模型没传）由运行时兜底为 false——不出卡、不终结。
 * 必然出卡的工具没有 show_ui，一律按出卡处理。
 *
 * 卡片是**非轮次阻塞**的：卡片发射后本轮按正常协议走完，
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
 * 子类必须复写两处：[toolSchema] 声明业务入参、[buildCard] 完成 UI 映射；
 * [getResult] 取业务数据（不复写则本轮无数据可回执，此时只得出卡，
 * 典型如卡片即问询的场景）；发射接线由 final [execute] 统一收口，
 * 卡片落在工具调用的 ToolCallStart/End 区间内，锚点天然对齐。
 *
 * 按需出卡时 show_ui 由基类并入给模型的 schema（子类的业务 schema 一行不改）。
 * 模型要出卡时传 true——「查天气」过程中顺手调一次的定位工具不必出卡，
 * 只有本次结果本身就是给用户的答案（或要向用户提问）才传 true。
 *
 * 卡片在工具结果 metadata 中交付：卡片 IR 编码进 [ToolExecutionResult.metadata] 的
 * [CARD_METADATA_KEY]，随事件流（[io.github.yeyi.agent.AgentEvent.ToolCallEnd]）实时送达消费方渲染，
 * 同时经归档层落到 [MemoryEntry.metadata] 走记忆链路原生持久化——重启恢复时直接读取渲染。
 * 消费方读取一律走 [decodeCard]（[ToolExecutionResult.decodeCard] / [MemoryEntry.decodeCard]），
 * 无需感知键名细节。ToolResult 本身保持模型线格式纯净，卡片不污染上行载荷。
 * 子 agent 继承父 agent 的工具实例（连同产卡行为），与继承父的 llmProvider、memory 同属一种继承。
 * 产卡工具实例因此是 agent 私有的：不要在工具里持有跨 agent 的状态（缓存、限流计数）。
 */
public abstract class UiTool : Tool, FinalizableTool {

    internal companion object {
        /** 回执里告知模型「卡片已展示给用户」，避免它把同样内容在正文里再讲一遍。 */
        internal const val CARD_SHOWN_NOTE =
            "(this has already been shown to the user as a ui-card)"

        /** 框架级参数名：模型据此逐次决定产卡工具本轮出不出卡。 */
        internal const val SHOW_UI_PARAM = "show_ui"

        /** show_ui 的参数描述：交给模型的判据。 */
        internal const val SHOW_UI_DESCRIPTION =
            "是否把本调用的结果或参数以卡片的形式展示给用户（必传）。用法：\n" +
                    "1.当需要同时发起多个本调用（同名不同参）时，传 false（无须出卡）\n"+
                    "2.当本调用本身完全符合用户意图时，传 true（出卡，即最终回复）\n" +
                    "3.当本调用只是推理的中间步骤时，传 false（此时只回执数据，不出卡）"


        /**
         * 卡片在工具结果 metadata 中的固定键名。
         *
         * 写入侧与读取侧都在本模块内维护：[UiTool] 执行时把卡片 IR 编码进
         * [ToolExecutionResult.metadata] 的此键，由归档层提取到
         * [io.github.yeyi.agent.memory.MemoryEntry.metadata] 走记忆链路原生持久化；
         * 消费方（如界面层）恢复时经 [MemoryEntry.decodeCard] 读取解码渲染，
         * 无需旁路独立持久化文件。本键对模块外部不可见，读取一律走 [MemoryEntry.decodeCard]。
         */
        internal const val CARD_METADATA_KEY: String = "card"
    }

    /**
     * 业务入参 schema：子类声明自己的入参结构。
     *
     * 模型看到的形态是它与框架参数 show_ui 合并后的结果（见 [parametersSchema]），
     * 子类只管业务入参，不必操心框架参数。
     */
    protected abstract val toolSchema: ToolParameters

    /**
     * 是否必然出卡（每次调用都出卡并终结本轮，不给模型 show_ui 参数）。
     *
     * false（默认）：schema 并入必传参数 show_ui，出卡与否、终结与否全看模型表态；
     * true：工具必然出卡并终结本轮，schema 不并入 show_ui，模型也无从讨价还价。
     *
     * 必然出卡者适用于「内容本身就是给用户的最终答案」的工具（如生成卡片工具），
     * 需要中途取数的中间步骤工具必须保持 false。
     */
    protected open val alwaysShowUi: Boolean = false

    /**
     * 模型可见的 schema：业务 schema 与框架参数 show_ui 的合并结果；必然出卡时原样透出业务 schema。
     *
     * final：合并由基类统一收口，子类只声明 [toolSchema]，不直接覆写模型的入参形态。
     */
    final override val parametersSchema: ToolParameters by lazy {
        if (alwaysShowUi) toolSchema else withShowUi(toolSchema)
    }

    /**
     * 业务结果：本轮工具结果的唯一来源，取数结果原样存入 agent 记忆。
     *
     * 返回非 null：回执文本是取数结果的字符串形式，存入记忆，模型后续轮次据此读到具体信息。
     *
     * 不复写（默认 null）：本轮没有业务数据可回执，典型如卡片即问询的场景
     * （卡片承载问题本身，没有业务结果可讲）。此时只能靠卡片交付，模型须传 show_ui=true，
     * 回执只带「卡片已展示给用户」；既无数据又不出卡则本轮什么都没交付，基类判失败。
     *
     * 业务异常一律直接抛出（由框架统一转为错误回执，拒绝终结并让模型感知失败重试），
     * 不要吞掉异常或返回 null 伪装成功。
     */
    protected open suspend fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement? = null

    /**
     * 将本轮结构化数据映射为卡片 IR。
     *
     * 模型要求出卡（show_ui=true）时必须返回非空卡片：不出卡就没有交付给用户的东西，
     * 故基类把「未产出卡片」判为失败（错误回执，拒绝终结，模型下一轮感知并重试）。
     *
     * [result] 由 [getResult] 返回后传入，是构造卡片所需业务数据的唯一来源：
     * 需要结果数据构造卡片时，应在 [getResult] 中返回工具结果数据，这里直接使用 [result] 参数即可，
     * 不要在本方法内自行取数（如重新 [getResult]）。
     *
     * @param arguments 本轮 tool 调用的业务入参（框架参数 show_ui 已被剥掉）
     * @param result [getResult] 的取数结果
     * @param context 本轮执行上下文
     */
    protected abstract suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext
    ): CardIr?

    /**
     * 可终结裁决：由出卡与否与执行成败共同决定（必然出卡的工具恒出卡）。
     *
     * 出卡即终结：卡片已经交到用户手上，本轮到此为止，等用户回复或点选卡片动作；
     * 不出卡就不终结——模型看到回执后自行决定下一步（中间步骤工具调用不打断推理链）。
     * 执行失败一律不终结（模型下一轮感知失败并重试）。
     */
    final override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean =
        super.finalizable(arguments, result) && showCard(arguments)

    /**
     * 模板接线：业务执行 → 回执定稿 → 产卡路由发射 → 回执。
     *
     * 固定执行顺序保证卡片发射时序位于 ToolCallStart/End 区间内；回执文本照常返回进记忆，卡片走旁路。
     * 出不出卡由模型逐次决定（show_ui）：true 则出卡并终结，false 则只回执数据、本轮继续。
     * 回执语义由 [getResult] 返回值决定：有数据即回执数据文本，模型后续轮次据此读到具体信息；
     * 无数据（卡片即问询的场景）则回执只有「卡片已展示给用户」——没有数据可讲。
     * 本轮产了卡时，回执末尾追加「卡片已展示给用户」的告知，避免模型在正文里
     * 把卡片内容再讲一遍；未产卡不追加，避免谎报。
     *
     * 回执定稿在发卡之前：回执不成立时本轮根本不该把卡发到用户手上——
     * 否则用户看到卡片，模型却收到失败回执去重试，卡片会被重复发出。
     *
     * 发射通道在构造期已定死；通道上没有订阅者属装配配置
     * 错误（产卡工具要求使用方为该 agent 备好 sink 并保持订阅），直接抛 [IllegalStateException]
     * fail-fast 暴露，绝不静默丢弃卡片；异常向上传播由工具执行链路统一转错误回执，
     * [finalizable] 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val showCard = showCard(arguments)
        // 业务侧只看到业务入参：show_ui 是框架与模型之间的约定，不该渗进业务解码
        val origArgs = withoutShowUi(arguments)
        val result = getResult(origArgs, context)
        // 又无数据又不出卡：本轮什么都没交付给用户，模型也无从读到什么。
        // 按需出卡的工具，措辞指向模型侧的修法：带 show_ui=true 重新调用。
        if (!showCard && result == null) {
            error("$name produced neither a result nor a card: call it again with show_ui=true")
        }
        val card = if (showCard) buildCard(origArgs, result, context) else null
        // 模型要求出卡就必须真有卡片：没有卡片就没有交付给用户的东西
        if (showCard && card == null) {
            error("$name produced no card: a card tool must build a card in buildCard")
        }
        // 产了卡就要告知模型卡片已送达，避免它把同样的内容在正文里再讲一遍；
        // 未产卡不追加，避免谎报
        val content = result?.toString()
        val receipt = when {
            content != null && card != null -> "$content $CARD_SHOWN_NOTE"
            content != null -> content
            else -> CARD_SHOWN_NOTE
        }
        // 卡片内嵌进结果元数据（CARD_METADATA_KEY），随 ToolCallEnd 事件实时送达
        // 消费方渲染，同时由归档层提取到 MemoryEntry 顶层 metadata 随记忆链路原生持久化——
        // 实时与恢复同源同键，重启恢复时直接从记忆条目读取渲染，无需旁路独立持久化文件。
        // 卡片锚点天然对齐：卡片绑定在对应工具调用的结果消息上，消息顺序即渲染顺序。
        val metadata = card?.let { encodeCard(it) }
        return ToolExecutionResult.success(receipt, metadata)
    }

    /**
     * 本轮是否出卡：必然出卡的工具恒为 true；
     * 按需出卡看模型传的 show_ui，模型没传时兜底为不出卡、不终结。
     */
    private fun showCard(arguments: JsonElement): Boolean =
        alwaysShowUi ||
                ((arguments as? JsonObject)?.get(SHOW_UI_PARAM) as? JsonPrimitive)?.booleanOrNull ?: false

    /**
     * 业务入参：剥掉框架参数 show_ui。
     *
     * show_ui 是框架与模型之间的约定，不该渗进业务解码——
     * 否则每个严格解码的业务工具都得为这个多出来的键放宽解码。
     */
    private fun withoutShowUi(arguments: JsonElement): JsonElement {
        if (arguments !is JsonObject) return arguments
        val origArgs = arguments.filterKeys { it != SHOW_UI_PARAM }
        return if (origArgs.size == arguments.size) arguments else JsonObject(origArgs)
    }

    /**
     * 在业务 schema 的 properties 里并入 show_ui，并让它进 required。
     *
     * show_ui 是框架参数（模型据此逐次决定本轮出不出卡），不是业务入参：
     * 子类的业务 schema 一行不改，模型却能多传这一个参数。
     * 声明为必传：模型每次调用都得表态出不出卡，缺省由运行时兜底为 false（不出卡、不终结）。
     *
     * 只附加、不覆写：业务 schema 的其余内容（`$defs`/`$ref`/`additionalProperties`/
     * 各属性的完整定义）原样保留。两处装配错误 fail-fast 抛 [IllegalArgumentException]：
     * 业务 schema 不是合法 JSON 对象（并入不了 show_ui，模型就永远看不到这个参数），
     * 以及业务 schema 自带同名 show_ui 属性（框架版本会静默覆盖它）。
     */
    private fun withShowUi(schema: ToolParameters): ToolParameters {
        val base = when (schema) {
            is ToolParameters.Empty -> buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            }

            is ToolParameters.JsonSchema -> {
                val parsed = runCatching { Json.parseToJsonElement(schema.schema) }.getOrNull()
                    ?: throw IllegalArgumentException(
                        "$name 的 toolSchema 不是合法 JSON，无法并入框架参数 $SHOW_UI_PARAM"
                    )
                parsed as? JsonObject
                    ?: throw IllegalArgumentException(
                        "$name 的 toolSchema 必须是 JSON 对象，无法并入框架参数 $SHOW_UI_PARAM"
                    )
            }
        }
        val properties = (base["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        require(SHOW_UI_PARAM !in properties) {
            "$name 的 toolSchema 已有同名属性 $SHOW_UI_PARAM，与框架参数冲突"
        }
        val showUiProperty = buildJsonObject {
            put("type", "boolean")
            put("description", SHOW_UI_DESCRIPTION)
        }
        val required = ((base["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                + SHOW_UI_PARAM).distinct()
        val merged = JsonObject(
            base +
                    ("properties" to JsonObject(properties + (SHOW_UI_PARAM to showUiProperty))) +
                    ("required" to JsonArray(required.map { JsonPrimitive(it) }))
        )
        return ToolParameters.JsonSchema(merged.toString())
    }
}

/**
 * 卡片 IR 编码进工具结果 metadata 的固定写法：写入侧统一走这里，读取侧走 [decodeCard]。
 *
 * 产卡工具（[UiTool] 子类）与模型生成卡片的工具（[GenUiTool]）共用这一条通道，
 * 消费方只认 [UiTool.CARD_METADATA_KEY] 一个键。
 */
internal fun encodeCard(card: CardIr): JsonObject =
    buildJsonObject { put(UiTool.CARD_METADATA_KEY, Json.encodeToJsonElement(card)) }

/**
 * 从工具结果 metadata 读取产卡工具内嵌的卡片 IR；无卡片返回 null。
 *
 * 与 [UiTool] 的写入互为镜像：产卡工具把卡片 IR 编码进 [UiTool.CARD_METADATA_KEY]，
 * 随 [io.github.yeyi.agent.AgentEvent.ToolCallEnd] 实时送达消费方渲染。
 * metadata 携带该键但内容非法时直接抛序列化异常（fail-fast）。
 */
public fun ToolExecutionResult.decodeCard(): CardIr? = metadata.decodeCard()

/**
 * 从记忆条目顶层 metadata 读取产卡工具内嵌的卡片 IR；无卡片返回 null。
 *
 * 与 [UiTool] 的写入互为镜像：产卡工具把卡片 IR 编码进 [UiTool.CARD_METADATA_KEY]
 * （经归档层落到本条目顶层 metadata），消费方（如界面层）恢复时调用本扩展解码渲染，
 * 无需感知键名细节。metadata 携带该键但内容非法时直接抛序列化异常（fail-fast）。
 */
public fun MemoryEntry.decodeCard(): CardIr? = metadata.decodeCard()

private fun JsonObject?.decodeCard(): CardIr? {
    val cardJson = this?.get(UiTool.CARD_METADATA_KEY) ?: return null
    return Json.decodeFromJsonElement(CardIr.serializer(), cardJson)
}
