plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":agent:core"))                       // AgentBuilder / AgentPlugin / Tool
    implementation(libs.kotlinx.serialization.json)   // 只用 JsonElement 运行时 API
    implementation(libs.kotlinx.coroutines.core)      // Mutex / runBlocking

    testImplementation(testFixtures(project(":agent:core")))   // FakeLlmProvider
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
