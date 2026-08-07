plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.yeyi.agent.gateway"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {

    api(project(":gateway:core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.oapi.sdk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
