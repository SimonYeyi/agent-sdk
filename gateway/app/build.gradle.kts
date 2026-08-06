import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.yeyi.agent.gateway.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yeyi.agent.gateway.app"
        minSdk = 26
        versionCode = 1
        versionName = "0.1.0"

        val localProps = Properties().apply {
            val f = file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun raw(key: String) = localProps.getProperty(key).orEmpty()
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${raw("ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "ANTHROPIC_BASE_URL", "\"${raw("ANTHROPIC_BASE_URL")}\"")
        buildConfigField("String", "ANTHROPIC_MODEL", "\"${raw("ANTHROPIC_MODEL")}\"")
        buildConfigField("String", "FEISHU_APP_ID", "\"${raw("FEISHU_APP_ID")}\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"${raw("FEISHU_APP_SECRET")}\"")
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/DEPENDENCIES.txt",
            )
        }
    }
}

dependencies {
    implementation(project(":agent:core"))
    implementation(project(":agent:session"))
    implementation(project(":gateway:core"))
    implementation(project(":agent:providers:anthropic"))
    implementation(project(":gateway:platforms:feishu"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
}
