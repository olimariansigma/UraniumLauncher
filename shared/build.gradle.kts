import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Generates BuildInfo.kt so the launcher version and the build-time API credentials live in one
// place. The credentials come from the environment (CI secrets) or ~/.gradle/gradle.properties and
// are never committed; both can also be supplied at runtime (see README).
val appVersion = providers.gradleProperty("uranium.version")
val msaClientId = providers.environmentVariable("URANIUM_MSA_CLIENT_ID")
    .orElse(providers.gradleProperty("uranium.msaClientId")).orElse("")
val curseForgeKey = providers.environmentVariable("CURSEFORGE_API_KEY")
    .orElse(providers.gradleProperty("uranium.curseforgeApiKey")).orElse("")
val shareServerUrl = providers.environmentVariable("URANIUM_SHARE_URL")
    .orElse(providers.gradleProperty("uranium.shareUrl")).orElse("")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    val version = appVersion
    val clientId = msaClientId
    val cfKey = curseForgeKey
    val shareUrl = shareServerUrl
    inputs.property("version", version)
    inputs.property("msaClientId", clientId)
    inputs.property("curseForgeKey", cfKey)
    inputs.property("shareUrl", shareUrl)
    outputs.dir(outputDir)
    doLast {
        // CurseForge keys contain '$', which would otherwise start a Kotlin string template.
        fun literal(value: String) = value.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val file = outputDir.get().file("dev/uranium/launcher/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.uranium.launcher
            |
            |object BuildInfo {
            |    const val NAME = "UraniumLauncher"
            |    const val VERSION = "${literal(version.get())}"
            |    const val MSA_CLIENT_ID = "${literal(clientId.get())}"
            |    const val CURSEFORGE_API_KEY = "${literal(cfKey.get())}"
            |    const val SHARE_URL = "${literal(shareUrl.get())}"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    android {
        namespace = "dev.uranium.launcher.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Run commonTest on the Android (host JVM) side as well.
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // Both targets are JVMs. JDK-backed `actual`s that are identical on Android and
        // desktop live in src/jvmShared and are compiled into each platform source set.
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}
