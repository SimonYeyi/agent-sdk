package io.github.yeyi.agent.config

public class AgentRegistry {
    private val definitions = mutableMapOf<String, AgentDefinition>()

    public fun register(definition: AgentDefinition) {
        check(!definitions.containsKey(definition.name)) {
            "Agent with name '${definition.name}' is already registered"
        }
        definitions[definition.name] = definition
    }

    public fun getOrNull(name: String): AgentDefinition? = definitions[name]
}
