plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // "default" 是 Java 关键字，namespace 不能含该段；源码包名 io.github.yeyi.agent.genui.adapters.default
    // 在 Kotlin 中合法（软关键字），保持不变，两者分离
    namespace = "io.github.yeyi.agent.genui.adapter.defaultadapter"
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
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}
