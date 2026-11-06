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

    // androidx.a2ui 协议引擎与模型（a2ui-engine / a2ui-model）；
    // a2ui.compose 官方 Compose 渲染制品（compose-runtime 快照状态层 / compose-ui 组件注册层）；
    // material3-a2ui 提供 A2uiSurface 顶层渲染入口 + 完整 Material A2uiBasicCatalogV1 目录实现，
    // 已随 1.0.0-alpha01（2026-09-23）发布到公开 Google Maven
    implementation(libs.a2ui.model)
    implementation(libs.a2ui.engine)
    implementation(libs.a2ui.compose.runtime)
    implementation(libs.a2ui.compose.ui)
    implementation(libs.a2ui.material3)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
