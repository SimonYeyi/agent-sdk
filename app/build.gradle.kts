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
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun p(key: String, default: String): String {
            val v = localProps.getProperty(key)?.trim().orEmpty()
            val unquoted = if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
            return unquoted.ifEmpty { default }
        }
        buildConfigField("String", "MODEL_PROVIDER", "\"${p("MODEL_PROVIDER", "openai")}\"")
        buildConfigField("String", "MODEL_BASE_URL", "\"${p("MODEL_BASE_URL", "")}\"")
        buildConfigField("String", "MODEL_API_KEY", "\"${p("MODEL_API_KEY", "")}\"")
        buildConfigField("String", "MODEL_NAME", "\"${p("MODEL_NAME", "gpt-4o-mini")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":agent"))
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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":agent")))
}
