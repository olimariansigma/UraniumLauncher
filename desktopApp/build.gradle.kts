import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val appVersion = providers.gradleProperty("uranium.version").get()

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.slf4j.nop)
}

compose.desktop {
    application {
        mainClass = "dev.uranium.launcher.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "UraniumLauncher"
            packageVersion = appVersion
            description = "Uranium Launcher - a Minecraft: Java Edition launcher"
            vendor = "Uranium Launcher"
            // jlink cannot see reflective/service-loaded use, so the modules are listed by hand:
            // TLS elliptic curves (jdk.crypto.ec), DNS (java.naming), Unsafe for coroutines and
            // Skiko (jdk.unsupported) and the OS memory bean (jdk.management).
            modules(
                "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
                "java.net.http", "jdk.crypto.ec", "jdk.management", "jdk.unsupported",
            )

            windows {
                menuGroup = "Uranium Launcher"
                shortcut = true
                menu = true
                perUserInstall = true
                dirChooser = true
                // Stable GUID so newer installers upgrade an existing install in place.
                upgradeUuid = "6f0a4c1e-52b7-4d0b-9f3e-8c2f5b7a1d94"
            }
        }
    }
}
