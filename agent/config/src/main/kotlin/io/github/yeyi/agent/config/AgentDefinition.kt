package io.github.yeyi.agent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AgentDefinition(
    val name: String,
    val version: Int = 0,
    val persona: String,
    val provider: Provider,
    val maxIterations: Int = 20,
    val maxMemoryRounds: Int = 20,
    val tools: List<String>? = null,
    val toolsets: List<String>? = null,
    val skills: List<String>? = null,
    val subagents: List<String>? = null,
    val mcps: List<Mcp>? = null,
) {
    @Serializable
    public data class Provider(
        val name: String,        // "anthropic" | "openai"
        val baseUrl: String,
        val model: String,
        val apiKey: String
    )

    @Serializable
    public data class Mcp(
        val name: String,
        val description: String,
        val transport: Transport
    ) {
        @Serializable
        public sealed interface Transport {
            @Serializable
            @SerialName("stdio")
            public data class Stdio(
                val command: String,
                val args: List<String> = emptyList(),
                val env: Map<String, String> = emptyMap()
            ) : Transport

            @Serializable
            @SerialName("http")
            public data class Http(
                val url: String,
                val headers: Map<String, String> = emptyMap()
            ) : Transport
        }
    }
}
