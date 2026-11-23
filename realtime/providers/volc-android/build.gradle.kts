plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.yeyi.agent.realtime.volc"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":realtime:core"))
    api(project(":realtime:providers:volc"))
    implementation(libs.speechengine.tob)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
}
