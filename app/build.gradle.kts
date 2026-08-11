plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.toolicious.labler"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.toolicious.labler.by288"
        minSdk = 26
        targetSdk = 36
        versionCode = 1010200
        versionName = "1.1.2"
        manifestPlaceholders["appName"] = "错题小印"
    }

    buildTypes {
        debug { manifestPlaceholders["appName"] = "错题小印" }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xplugin=${rootProject.file("tools/kotlin-serialization-compiler-plugin.jar").absolutePath}"
        )
    }
}

configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.6.4")
    resolutionStrategy.force("com.google.guava:guava:31.1-jre")
    // Keep Compose Foundation aligned with the 1.7.6 UI stack used by this app.
    // Material3 1.3.1 requests 1.7.2 transitively, but the verified offline
    // environment intentionally carries the complete 1.7.6 Foundation artifacts.
    resolutionStrategy.force("androidx.compose.foundation:foundation:1.7.6")
    resolutionStrategy.force("androidx.compose.foundation:foundation-layout:1.7.6")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.emoji2:emoji2-emojipicker:1.0.0-alpha03")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.6.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.6.3")
    implementation(files("libs/kotlinx-serialization-json-jvm-1.6.2.jar"))
    compileOnly(files("libs/zxing-compile-stubs.jar"))
    testImplementation("junit:junit:4.13.2")
}
