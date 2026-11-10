pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        mavenCentral { url=uri(providers.gradleProperty("mavenCentralUrl").orElse("https://repo.maven.apache.org/maven2").get()) }
        maven(providers.gradleProperty("googleMavenUrl").orElse(providers.environmentVariable("STACKTRACE_GOOGLE_MAVEN_URL")).orElse("https://dl.google.com/dl/android/maven2")) {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
            }
        }
    }
}
rootProject.name = "StackTraceStudio"
include(":shared")
include(":retrace")
include(":composeApp")
include(":server")
include(":webApp")
