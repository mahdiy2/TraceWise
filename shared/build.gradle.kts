plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}
kotlin {
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        wasmJsMain.dependencies { implementation(libs.kotlinx.browser) }
        jvmTest.dependencies { implementation(libs.ktor.client.mock); implementation(libs.coroutines.test) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
