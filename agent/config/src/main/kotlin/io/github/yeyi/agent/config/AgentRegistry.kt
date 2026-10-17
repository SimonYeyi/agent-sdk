package io.github.yeyi.agent.config

public class AgentRegistry {
    private val definitions = mutableMapOf<String, AgentDefinition>()

    public fun register(definition: AgentDefinition) {
        definitions[definition.name] = definition
    }

    public fun find(name: String): AgentDefinition? = definitions[name]
}
