import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.coroutines.test)
    implementation(project(":shared"))
    implementation(project(":retrace"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.koin.core)
}
compose.desktop {
    application {
        mainClass = "studio.stacktrace.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "StackTraceStudio"
            packageVersion = "0.1.0"
            modules("java.sql", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            description = "Android stack trace deobfuscation with official R8 Retrace"
            vendor = "StackTrace Studio"
            windows {
                perUserInstall = true
                dirChooser = true
                menu = true
                shortcut = true
                upgradeUuid = "312bd5af-8071-4f41-9343-ac2c705268de"
            }
            linux { packageName = "stacktrace-studio"; menuGroup = "Development" }
        }
    }
}
