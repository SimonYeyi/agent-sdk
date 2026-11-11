package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.isRenderable
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 生成卡片工具：把内容交给模型，由模型现场生成卡片 IR。
 *
 * 继承 [UiTool]，复用其产卡接线（BusinessResult → [buildCard] → 编码进 metadata → 终结本轮）：
 * UiTool 的卡片由开发者手写，适合结构固定、字段已知的场景（天气卡、定位卡）；
 * 本工具的卡片由模型现场排版，适合「把这段结果做成一张卡」这种开放内容——
 * 不必为每种内容各写一个工具。
 *
 * 流程：主模型把要展示的内容交给本工具 → 本工具用 agent 的 LLM provider
 * 再发一次请求（系统提示给出组件词汇与输出契约，用户消息给出待排版内容）
 * → 模型回的 JSON 解码为组件树 → 卡片编码进结果 metadata，随事件流送达渲染。
 *
 * 与普通 UiTool 的不同：本工具没有 show_ui（[alwaysShowUi] = true）——
 * 它就是「出卡」这件事本身，必然产卡、必然终结本轮（卡片交到用户手上，本轮到此为止）。
 *
 * 组件词汇由构造参数 [componentSpec] 决定：IR 是后端中立的，工具不知道
 * 渲染层注册了哪些组件，故默认给出两个渲染后端都支持的交集组件集；
 * 渲染层有更多组件时，由使用方把完整词汇表注入进来。
 *
 * 生成失败的处理分两类：
 * - 模型没回内容（reply 为空）：无可降级素材，抛异常，由工具执行链路统一转为错误回执，
 *   模型下一轮感知失败并重试。
 * - 模型回了内容但格式不规范（不是合法 JSON、不是 JSON 对象、缺 root 节点、
 *   组件树非法、根节点动作文案为空等生成式模型常见的格式抖动）：降级为一棵只装
 *   原始 content 的 Text 节点——排版意图丢了，但用户至少能看到内容本身，
 *   不至于因生成式模型的格式失误让整轮空转。intent 在降级路径中丢弃（它只是排版意图，
 *   不影响内容呈现）。
 *
 * @param componentSpec 交给生成模型的组件词汇表（组件名与可用属性说明）
 */
public class GenUiTool(componentSpec: String? = null) : UiTool() {

    override val name: String = "gen_ui_tool"

    override val description: String = "生成 UI 卡片。用法：\n" +
            "1.利用自身已有知识作答，无须调用，直接输出最终回复即可\n" +
            "2.调用了其他工具，且最终回复的核心是数字、列表项、信息点，必须调用本工具\n" +
            "3.此外，如果最终回复是大块长文本，无须调用本工具"

    override val toolSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {"type":"object",
         "properties":{
           "content":{"type":"string","description":"要展示给用户的内容：卡片承载的正文、要点或数据说明"},
           "intent":{"type":"string","description":"展示意图（可选）：期望的卡片形态，如「做成对比表」「做成待办清单」；留空由生成器自行设计"}},
         "required":["content"]}
        """.trimIndent()
    )

    private val componentSpec = componentSpec ?: DEFAULT_COMPONENT_SPEC

    /** 本工具必然出卡：不给模型 show_ui 参数。 */
    override val alwaysShowUi: Boolean = true

    /** 产卡前先把内容交给生成模型排版，解码出组件树。 */
    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext
    ): CardIr = generateCard(parseRequest(arguments), context)

    /** 入参解析：content 必传，intent 可选。 */
    private fun parseRequest(arguments: JsonElement): GenUiRequest {
        val obj = arguments as? JsonObject
            ?: throw IllegalArgumentException("gen_ui_tool 的入参必须是 JSON 对象")
        val content = (obj["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("gen_ui_tool 缺少必传参数 content")
        val intent = (obj["intent"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return GenUiRequest(content = content, intent = intent)
    }

    /** 生成卡片：把内容交给模型排版，解码模型回的组件树。 */
    private suspend fun generateCard(
        request: GenUiRequest,
        context: ToolExecutionContext
    ): CardIr {
        val response = context.agentContext.llmProvider.chat(
            ChatRequest(
                messages = listOf(
                    ChatMessage.System("$UI_GENERATOR_CONTRACT\n\n$componentSpec"),
                    ChatMessage.User(listOf(ContentPart.Text(userPrompt(request)))),
                ),
            )
        )
        val reply = response.message.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("UI 生成模型没有返回内容")
        // 卡片锚点用本次工具调用 id：与工具结果消息一一对应，渲染顺序即消息顺序
        val root = try {
            parseRoot(reply)
        } catch (_: Exception) {
            TreeNode(
                component = "Card", children = listOf(
                    TreeNode("Text", bindings = mapOf("content" to JsonPrimitive(request.content)))
                )
            )
        }
        return CardIr(instanceId = context.toolCallId, root = root)
    }

    /** 待排版内容交给生成模型的话术：内容与展示意图两段。 */
    private fun userPrompt(request: GenUiRequest): String = buildString {
        append("请把下面的内容排布成一张卡片。\n\n内容：\n")
        append(request.content)
        request.intent?.takeIf { it.isNotBlank() }?.let {
            append("\n\n展示意图：")
            append(it)
        }
    }

    /**
     * 从模型回复中取出组件树。
     *
     * 模型常把 JSON 包在 markdown 代码块里，先剥壳再解析；
     * 顶层既可以是 {"root": {...}}，也可以直接是节点对象。
     */
    private fun parseRoot(reply: String): TreeNode {
        val raw = extractJson(reply)
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: throw IllegalStateException("UI 生成模型返回的不是合法 JSON：${raw.take(200)}")
        val obj = element as? JsonObject
            ?: throw IllegalStateException("UI 生成模型返回的不是 JSON 对象：${raw.take(200)}")
        val rootElement = obj["root"]
            ?: obj.takeIf { "component" in it }
            ?: throw IllegalStateException("UI 生成模型返回的 JSON 缺少 root 节点：${raw.take(200)}")
        val root = runCatching {
            Json.decodeFromJsonElement(TreeNode.serializer(), rootElement)
        }.getOrNull()
            ?: throw IllegalStateException("UI 生成模型返回的 root 不是合法组件树：$rootElement")
        if (!root.isRenderable()) {
            throw IllegalStateException("UI 生成模型返回的卡片根节点动作文案为空，整棵不会渲染")
        }
        return root
    }

    /** 剥掉 markdown 代码块外壳；没有外壳时截取首个 { 到末个 } 之间的 JSON。 */
    private fun extractJson(reply: String): String {
        val trimmed = reply.trim()
        FENCE.find(trimmed)?.let { return it.groupValues[1].trim() }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0..<end) trimmed.substring(start, end + 1) else trimmed
    }

    private companion object {
        /** 模型把 JSON 包在 markdown 代码块里的常见形态。 */
        val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

        /** 输出契约：与组件词汇无关的固定部分（格式 + 排版规则）。 */
        val UI_GENERATOR_CONTRACT: String = """
            你是 UI 生成器：把给定内容排布成一张卡片的组件树。

            只输出一个 JSON 对象，不要解释文字，不要 markdown 代码块。格式：
            {"root": {"component": "Card", "bindings": {}, "children": [], "action": null}}

            节点结构：
            - component：组件名
            - bindings：组件属性表
            - children：子节点数组
            - action：动作对象 {"label": "动作文案", "payload": "动作参数。自由文本，和 label 一同作为回流文本，格式：label(payload)"}，无动作写 null

            排版规则：
            1. 层次不超过 3 层（Card → Column/Row → Text/Button/Divider）。
            2. 一张卡只讲一件事：先标题，再要点，需要用户操作时再加 Button。
            3. action 只出现在 Button 上；payload 只在动作确实带参数时给，否则省略。
            4. 动作文案不能为空——文案为空的节点整棵不会被渲染。
        """.trimIndent()
    }
}

/** 本工具的入参：待排版内容与可选的展示意图。 */
private data class GenUiRequest(
    val content: String,
    val intent: String?,
)

/**
 * 默认组件词汇表：两个渲染后端（自研与 a2ui）都支持的交集组件集。
 *
 * 只列交集是刻意的：目录里没有的组件会被渲染层丢弃（a2ui 直接不投影，
 * 自研后端渲染成占位文本），列进来等于让模型生成看不见的节点。
 * 渲染层组件更多时，把完整词汇表注入 [GenUiTool] 的构造参数。
 */
private val DEFAULT_COMPONENT_SPEC: String = """
    可用组件与属性：
    - Card：卡片外壳，整棵树的根用它。bindings：spacing（整数，元素间距）、padding（整数，内边距）
    - Column：纵向容器。bindings：spacing（整数）、padding（整数）、arrangement（start/center/end/spaceBetween/spaceAround/spaceEvenly）、horizontalAlignment（start/center/end）
    - Row：横向容器。bindings：spacing（整数）、padding（整数）、arrangement（start/center/end/spaceBetween/spaceAround/spaceEvenly）、verticalAlignment（start/center/end）
    - Text：文本。bindings：content（必填）、style（largeTitle/title1/title2/title3/headline/body/callout/subheadline/footnote/caption）、weight（regular/medium/semibold/bold）、color（primary/secondary/blue/green/red/gray 或 #RRGGBB）、align（start/center/end）、maxLines（整数）
    - Button：按钮，按钮文案写在 action.label（必填）。bindings：variant（primary/secondary/bordered/destructive/plain）
    - Divider：分隔线。bindings：color
""".trimIndent()
