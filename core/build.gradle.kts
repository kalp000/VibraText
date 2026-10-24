import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // StateFlow / coroutines are part of core's public API (PlaybackController).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Set UPDATE_DOCS=1 to regenerate docs/ENCODING_TABLE.md from the code.
    environment("UPDATE_DOCS", System.getenv("UPDATE_DOCS") ?: "")
    systemProperty("docs.dir", rootProject.file("docs").absolutePath)
}
