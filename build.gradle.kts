plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    plugins.withType(JavaPlugin::class.java).configureEach {
        val archivePath = path.removePrefix(":").replace(":", "-")
        tasks.withType(Jar::class.java).configureEach {
            archiveBaseName.set(archivePath)
        }
    }
}
