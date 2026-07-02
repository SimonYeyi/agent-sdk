import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.yeyi.agent.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yeyi.agent.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        val localProps = Properties().apply {
            val f = file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun raw(key: String) = localProps.getProperty(key).orEmpty()
        buildConfigField("String", "MODEL_PROVIDER", "\"${raw("MODEL_PROVIDER")}\"")
        buildConfigField("String", "MODEL_BASE_URL", "\"${raw("MODEL_BASE_URL")}\"")
        buildConfigField("String", "MODEL_API_KEY", "\"${raw("MODEL_API_KEY")}\"")
        buildConfigField("String", "MODEL_NAME", "\"${raw("MODEL_NAME")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent"))
    implementation(project(":session"))
    implementation(project(":skill"))
    implementation(project(":hook"))
    implementation(project(":mcp"))
    implementation(project(":subagent"))
    implementation(project(":providers:openai"))
    implementation(project(":providers:anthropic"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.ktor.client.logging)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":agent")))
}
