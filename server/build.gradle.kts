plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(project(":retrace"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.koin.core)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}
application { mainClass.set("studio.stacktrace.server.MainKt") }
