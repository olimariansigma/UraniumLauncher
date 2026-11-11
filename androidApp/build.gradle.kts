plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val appVersion = providers.gradleProperty("uranium.version").get()

android {
    namespace = "dev.uranium.launcher.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.uranium.launcher"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        // major * 10000 + minor * 100 + patch, so every release installs over the previous one.
        versionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
        versionName = appVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
