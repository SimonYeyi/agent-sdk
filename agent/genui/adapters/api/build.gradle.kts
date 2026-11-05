plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.yeyi.agent.genui.adapter.api"
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
    api(project(":agent:genui:ir"))

    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
}
