plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.yeyi.agent.realtime.volc"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21); explicitApi() }
}

dependencies {
    api(project(":realtime:core"))
    api(project(":realtime:providers:volc"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
}

// NOTE: The speechengine_tob dependency must be manually configured:
// 1. Download the SDK from Volc Developer Portal
// 2. Run: maven install:mvn-install-file -Dfile=speechengine_tob-0.0.15.0.aar -DgroupId=com.bytedance.speechengine -DartifactId=speechengine_tob -Dversion=0.0.15.0 -Dpackaging=aar
// Or configure the Volc private Maven repository in your Gradle settings
// See: realtime/providers/volc_android_sdk.md for SDK setup instructions
