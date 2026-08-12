plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSerializationCompiler = rootProject.file(".local-build/jars/kotlin-serialization-compiler-plugin.jar")
if (localSerializationCompiler.exists()) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        @Suppress("DEPRECATION")
        kotlinOptions.freeCompilerArgs += "-Xplugin=${localSerializationCompiler.absolutePath}"
    }
} else {
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
}


android {
    namespace = "io.github.soulxyz.xyprt"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.soulxyz.xyprt"
        minSdk = 26
        targetSdk = 36
        versionCode = 1020100
        versionName = "1.2.1"
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
    val localSerializationJson = rootProject.file(".local-build/jars/kotlinx-serialization-json-jvm-1.6.2.jar")
    if (localSerializationJson.exists()) {
        implementation(files(localSerializationJson))
    } else {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    }

    // LabelRenderer uses ZXing through reflection. Public builds resolve it normally;
    // the private offline build injects the runtime classes after assembleDebug.
    val localZxingMarker = rootProject.file(".local-build/USE_INJECTED_ZXING")
    if (!localZxingMarker.exists()) {
        runtimeOnly("com.google.zxing:core:3.5.3")
    }
    testImplementation("junit:junit:4.13.2")
}
