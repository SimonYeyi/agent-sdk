import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.yeyi.agent.demo.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yeyi.agent.demo.agent"
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
        buildConfigField("String", "IOT_HA_ENDPOINT", "\"${raw("IOT_HA_ENDPOINT")}\"")
        buildConfigField("String", "IOT_HA_TOKEN", "\"${raw("IOT_HA_TOKEN")}\"")
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
    implementation(project(":agent:core"))
    implementation(project(":agent:session"))
    implementation(project(":agent:skill"))
    implementation(project(":agent:hook"))
    implementation(project(":agent:mcp"))
    implementation(project(":agent:subagent"))
    implementation(project(":agent:providers:openai"))
    implementation(project(":agent:providers:anthropic"))
    implementation(project(":agent:tool:serialization"))
    implementation(project(":agent:tool:compression"))

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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":agent:core")))
}
