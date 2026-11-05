plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.yeyi.agent.genui.adapter.a2ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        explicitApi()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":agent:genui:adapters:api"))

    // androidx.a2ui 引擎层纯 JVM 状态机；a2ui-compose-runtime / a2ui-compose-ui / material3-a2ui
    // 官方 Compose 渲染制品在公开 Google Maven 暂未发布，等待发布后接入 A2uiSurface 可组合入口
    implementation(libs.a2ui.model)
    implementation(libs.a2ui.engine)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
