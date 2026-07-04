plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("plugin.serialization")
}

group = "io.github.yeyi.agent"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":agent"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
