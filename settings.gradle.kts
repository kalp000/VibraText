pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VibraText"

// :core is a plain Kotlin/JVM module (encoding, playback logic, notification
// parsing, rules). It has no Android dependency, so its tests run on a laptop.
// :app is the Android application (UI, NotificationListenerService, vibrator).
include(":core", ":app")
