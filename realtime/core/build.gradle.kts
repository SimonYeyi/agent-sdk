plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

group = "io.github.yeyi.agent.realtime"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
