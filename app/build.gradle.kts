plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseOverlayDir = providers.gradleProperty("XYPRT_RELEASE_OVERLAY_DIR").orNull?.takeIf { it.isNotBlank() }

val localSerializationCompiler = rootProject.file(".local-build/jars/kotlin-serialization-compiler-plugin.jar")
if (localSerializationCompiler.exists()) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        @Suppress("DEPRECATION")
        kotlinOptions.freeCompilerArgs += "-Xplugin=${localSerializationCompiler.absolutePath}"
    }
} else {
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
}


// Keep Kotlin bytecode aligned with the Java 17 target even when the build host itself runs JDK 21.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    @Suppress("DEPRECATION")
    kotlinOptions.jvmTarget = "17"
}


android {
    namespace = "io.github.soulxyz.xyprt"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.soulxyz.xyprt"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("XYPRT_VERSION_CODE").orElse("1030008").get().toInt()
        versionName = providers.gradleProperty("XYPRT_VERSION_NAME").orElse("1.2.4").get()
        manifestPlaceholders["appName"] = "口袋小印"
        val updateApiBase = providers.gradleProperty("XYPRT_UPDATE_API_BASE_URL")
            .orElse("https://api.xyprt.5am.top")
            .get()
            .trimEnd('/')
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "UPDATE_API_BASE_URL", "\"$updateApiBase\"")
        val buildContractId = providers.gradleProperty("XYPRT_BUILD_CONTRACT_ID").orElse("source").get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "BUILD_CONTRACT_ID", "\"$buildContractId\"")
        val requestedAbis = providers.gradleProperty("XYPRT_ABIS").orElse("arm64-v8a,armeabi-v7a").get()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        ndk { abiFilters += requestedAbis }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("opensource") {
            dimension = "edition"
            buildConfigField("String", "BUILD_EDITION", "\"opensource\"")
            buildConfigField("boolean", "ENHANCED_SCANNER_AVAILABLE", "false")
            val channel = providers.gradleProperty("XYPRT_DISTRIBUTION_CHANNEL").orElse("community").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"$channel\"")
        }
        create("cocreator") {
            dimension = "edition"
            buildConfigField("String", "BUILD_EDITION", "\"cocreator\"")
            buildConfigField("boolean", "ENHANCED_SCANNER_AVAILABLE", "true")
            val channel = providers.gradleProperty("XYPRT_DISTRIBUTION_CHANNEL").orElse("cocreator").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"$channel\"")
        }
    }

    buildTypes {
        debug { manifestPlaceholders["appName"] = "口袋小印" }
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = providers.gradleProperty("XYPRT_COMPRESS_JNI").orElse("false").get().toBoolean()
    }

    sourceSets.getByName("main").apply {
        // Neutral release-contract hook. Public builds leave this unset.
        if (releaseOverlayDir != null) assets.srcDir(releaseOverlayDir)
    }
    val privateScanPro = rootProject.file("private-features/scan-pro/src/main/kotlin")
    if (privateScanPro.isDirectory) {
        sourceSets.getByName("cocreator").java.srcDir(privateScanPro)
    }

    // Public/online builds keep release lint enabled. The portable offline archive currently
    // lacks Google's lint-gradle artifact, so a private reproducible build may opt out explicitly.
    lint {
        checkReleaseBuilds = !providers.gradleProperty("XYPRT_OFFLINE_SKIP_LINT").orElse("false").get().toBoolean()
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
    val localSerializationJson = rootProject.file(".local-build/jars/kotlinx-serialization-json-jvm-1.6.2.jar")
    if (localSerializationJson.exists()) {
        implementation(files(localSerializationJson))
    } else {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    }

    // Offline/private builds can pin verified local artifacts without changing public Maven coordinates.
    val localZxing = rootProject.file(".local-build/jars/core-3.5.3.jar")
    if (localZxing.exists()) implementation(files(localZxing)) else runtimeOnly("com.google.zxing:core:3.5.3")

    val localOpenCv = rootProject.file(".local-build/aar/opencv-4.13.0.aar")
    if (localOpenCv.exists()) implementation(files(localOpenCv)) else implementation("org.opencv:opencv:4.13.0")

    if (rootProject.file("private-features/scan-pro").isDirectory) {
        val localOrt = rootProject.file(".local-build/aar/onnxruntime-android-1.24.1.aar")
        if (localOrt.exists()) add("cocreatorImplementation", files(localOrt))
        else add("cocreatorImplementation", "com.microsoft.onnxruntime:onnxruntime-android:1.24.1")

        // PRIVATE-only LiteRT runtime. The public OpenSource graph never resolves this file.
        val localLiteRt = rootProject.file("private-features/scan-pro/runtime/litert-2.1.5-java-compat.aar")
        if (localLiteRt.exists()) add("cocreatorImplementation", files(localLiteRt))
    }

    testImplementation("junit:junit:4.13.2")
}
