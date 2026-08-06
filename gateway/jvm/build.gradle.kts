plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.shadow)
}

group = "io.github.yeyi.agent.gateway"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("io.github.yeyi.agent.gateway.jvm.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":gateway:core"))
    implementation(project(":gateway:platforms:feishu"))
    implementation(project(":agent:providers:anthropic"))
    implementation(project(":agent:session"))
    implementation(project(":agent:core"))
    implementation(project(":agent:hook"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
}

tasks.shadowJar {
    archiveBaseName.set("gateway-jvm")
    archiveClassifier.set("all")
    mergeServiceFiles()
}