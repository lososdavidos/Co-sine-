pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sine"

// core: pure Kotlin/JVM — Subsonic client, accounts, downloads, play log.
//       No Android dependency, so it lifts into Kotlin Multiplatform later (§5.2a).
// app:  the Android client.
// desktop: Sine on Windows, macOS and Linux (§6.15), Compose for desktop over
//       the same core; phase/ is the design system both apps compile.
include(":core")
include(":app")
include(":desktop")
