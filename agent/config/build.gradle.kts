plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":agent:core"))
    api(project(":agent:providers:anthropic"))
    api(project(":agent:providers:openai"))
    api(project(":agent:tool:lazy-loading"))
    api(project(":agent:toolset"))
    api(project(":agent:mcp"))
    api(project(":agent:skill"))
    api(project(":agent:subagent"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
