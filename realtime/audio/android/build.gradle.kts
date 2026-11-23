plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.yeyi.agent.realtime.audio.android"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
    }

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

    implementation(libs.androidx.annotation)
    api(libs.kotlinx.coroutines.core)
}
