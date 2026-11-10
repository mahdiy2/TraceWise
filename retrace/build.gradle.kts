plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(libs.r8)
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}

application { mainClass.set("studio.stacktrace.retrace.MainKt") }
