package io.github.yeyi.agent.gateway.jvm

import java.io.File
import java.util.Properties

public data class GatewayDaemonConfig(
    val modelApiKey: String,
    val modelBaseUrl: String,
    val modelName: String,
    val modelProvider: String,
    val feishuAppId: String,
    val feishuAppSecret: String,
    val appStorageDir: String,
    val maxConcurrentSessions: Int,
) {
    public companion object {
        private const val GATEWAY_CONFIG_ENV = "GATEWAY_CONFIG"
        private val DEFAULT_APP_STORAGE_DIR =
            System.getProperty("user.home") + File.separator + ".gateway-jvm"
        private const val DEFAULT_MAX_CONCURRENT = 10

        private val REQUIRED_KEYS = listOf(
            "model.api.key",
            "model.base.url",
            "model.name",
            "feishu.app.id",
            "feishu.app.secret",
        )

        private val OPTIONAL_KEYS = listOf(
            "model.provider",
            "gateway.app.storage.dir",
            "gateway.max.concurrent.sessions",
        )

        /** Load config from properties file + env override, then validate. */
        public fun load(): GatewayDaemonConfig {
            val props = loadProperties()
            return assemble(props)
        }

        private fun loadProperties(): Properties {
            val props = Properties()
            val explicitPath = System.getenv(GATEWAY_CONFIG_ENV)
            val path = explicitPath ?: "application.properties"
            val file = File(path)
            when {
                explicitPath != null && !file.exists() -> {
                    throw IllegalStateException(
                        "GATEWAY_CONFIG=$explicitPath does not exist; refusing to fall back to defaults",
                    )
                }
                file.exists() -> file.inputStream().use { props.load(it) }
            }
            return props
        }

        private fun assemble(props: Properties): GatewayDaemonConfig {
            (REQUIRED_KEYS + OPTIONAL_KEYS).forEach { envOverride(props, it) }

            val missing = REQUIRED_KEYS.filter { props.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    buildString {
                        append("Missing required config keys: ")
                        append(missing.joinToString(", "))
                        append("\nSet via ")
                        append(missing.joinToString("/") { it.uppercase().replace('.', '_') })
                        append(" env vars, or in application.properties (path: ")
                        append(System.getenv(GATEWAY_CONFIG_ENV) ?: "application.properties")
                        append(")")
                    },
                )
            }

            return GatewayDaemonConfig(
                modelApiKey = props.getProperty("model.api.key").trim(),
                modelBaseUrl = props.getProperty("model.base.url").trim(),
                modelName = props.getProperty("model.name").trim(),
                modelProvider = props.getProperty("model.provider").trim(),
                feishuAppId = props.getProperty("feishu.app.id").trim(),
                feishuAppSecret = props.getProperty("feishu.app.secret").trim(),
                appStorageDir = props.getProperty("gateway.app.storage.dir", DEFAULT_APP_STORAGE_DIR).ifBlank { DEFAULT_APP_STORAGE_DIR },
                maxConcurrentSessions = props.getProperty("gateway.max.concurrent.sessions", DEFAULT_MAX_CONCURRENT.toString())
                    .toIntOrNull() ?: DEFAULT_MAX_CONCURRENT,
            )
        }

        /** Override property value with env var (uppercase, dot→underscore) if present. */
        private fun envOverride(props: Properties, key: String) {
            val envKey = key.uppercase().replace('.', '_')
            val envVal = System.getenv(envKey)
            if (!envVal.isNullOrBlank()) {
                props.setProperty(key, envVal)
            }
        }
    }
}
