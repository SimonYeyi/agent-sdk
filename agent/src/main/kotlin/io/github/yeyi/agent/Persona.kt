package io.github.yeyi.agent

public class Persona(private val role: String) {
    private var personality: String? = null
    private var domain: String? = null
    private val constraints: MutableList<String> = mutableListOf()
    private val extras: MutableList<Pair<String?, String>> = mutableListOf()

    public fun personality(text: String): Persona = apply { this.personality = text }
    public fun domain(text: String): Persona = apply { this.domain = text }
    public fun constraints(items: List<String>): Persona = apply { constraints.addAll(items) }
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
