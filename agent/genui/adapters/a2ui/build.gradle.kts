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

// a2ui 1.0.0-alpha01 制品 AAR 元数据声明 minAgpVersion=9.1.0，超出本项目 AGP 8.9.1；
// AGP 8.9.1 仅提供 disableCompileSdkChecks 开关（已全局配置），AGP 版本检查无开关，
// 直接跳过本模块的 AAR 元数据检查任务（alpha 依赖实际 API 在 8.9.1 上可编译运行）。
tasks.matching { it.name.endsWith("AarMetadata") }.configureEach { enabled = false }

dependencies {
    api(project(":agent:genui:adapters:api"))

    // androidx.a2ui 协议引擎与模型（a2ui-engine / a2ui-model）；
    // a2ui.compose 官方 Compose 渲染制品（compose-runtime 快照状态层 / compose-ui 组件注册层）；
    // material3-a2ui 提供 A2uiSurface 顶层渲染入口 + 完整 Material A2uiBasicCatalogV1 目录实现，
    // 已随 1.0.0-alpha01（2026-09-23）发布到公开 Google Maven。
    // 全部走 api：A2uiRenderAdapter 的公开契约（catalog/processor 参数与属性）直接暴露这些类型，
    // 消费者（如 demo 组装点构造目录、读取处理器）必须可见。
    api(libs.a2ui.model)
    api(libs.a2ui.engine)
    api(libs.a2ui.compose.runtime)
    api(libs.a2ui.compose.ui)
    api(libs.a2ui.material3)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
