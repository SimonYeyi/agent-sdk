package io.github.yeyi.agent.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("name")
    public sealed interface Provider {
        public val baseUrl: String
        public val model: String
        public val apiKey: String

        @Serializable
        @SerialName("openai")
        public data class OpenAi(
            override val baseUrl: String,
            override val model: String,
            override val apiKey: String
        ) : Provider

        @Serializable
        @SerialName("anthropic")
        public data class Anthropic(
            override val baseUrl: String,
            override val model: String,
            override val apiKey: String
        ) : Provider
    }

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
                val env: Map<String, String> = emptyMap(),
                val workingDirectory: String? = null
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
