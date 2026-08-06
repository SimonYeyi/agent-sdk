package io.github.yeyi.agent

/**
 * Agent 人设值对象，封装角色描述的各个组成部分。
 *
 * 支持以下字段配置（均为链式调用）：
 * - [personality]：性格描述，渲染为 `Personality: <text>`
 * - [domain]：领域描述，渲染为 `Domain: <text>`
 * - [constraints]：约束列表，渲染为 `Constraints:\n- item` 格式
 * - [extra]：附加文本段落，可选 label；无 label 时直接输出，有 label 时输出 `label: text`
 *
 * [toString] 将各字段按固定顺序拼接为多段文本，作为 system prompt 传给 LLM。
 *
 * 示例：
 * ```
 * Persona("你是一个 helpful 助手。")
 *     .personality("Friendly and concise.")
 *     .domain("Weather and travel.")
 *     .constraints(listOf("Don't recommend flights"))
 *     .extra("你可以使用以下技能：...", "Tools")
 * ```
 */
public class Persona(public val role: String) {
    private var personality: String? = null
    private var domain: String? = null
    private val constraints: MutableList<String> = mutableListOf()
    private val extras: MutableList<Pair<String?, String>> = mutableListOf()

    /** 设置性格描述，渲染为 `Personality: <text>`。多次调用后者覆盖前者。 */
    public fun personality(text: String): Persona = apply { this.personality = text }

    /** 设置领域描述，渲染为 `Domain: <text>`。多次调用后者覆盖前者。 */
    public fun domain(text: String): Persona = apply { this.domain = text }

    /** 添加约束项列表，渲染为 `Constraints:\n- item` 格式。多次调用累加。 */
    public fun constraints(items: List<String>): Persona = apply { constraints.addAll(items) }

    /**
     * 添加附加文本段落。
     *
     * @param text 段落内容
     * @param label 可选标签；有值时渲染为 `label: text`，为 null 时直接输出 text
     */
    public fun extra(text: String, label: String? = null): Persona =
        apply { extras.add(label to text) }

    override fun toString(): String {
        val sections = mutableListOf<String>()
        sections += role
        personality?.let { sections += "Personality: $it" }
        domain?.let { sections += "Domain: $it" }
        if (constraints.isNotEmpty()) {
            sections += "Constraints:\n" + constraints.joinToString("\n") { "- $it" }
        }
        extras.forEach { (label, text) ->
            sections += if (!label.isNullOrBlank()) "$label: $text" else text
        }
        return sections.joinToString("\n\n")
    }
}
