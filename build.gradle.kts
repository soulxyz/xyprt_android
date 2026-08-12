buildscript {
    dependencies {
        // Public/network builds load the normal Kotlin serialization Gradle plugin.
        // The private offline build uses the bundled compiler plugin directly.
        if (!file(".local-build/jars/kotlin-serialization-compiler-plugin.jar").exists()) {
            classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.20")
        }
    }
}

plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
