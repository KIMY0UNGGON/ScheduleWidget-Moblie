plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.schedulewidget.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.schedulewidget.mobile"
        minSdk = 26
        targetSdk = 36
        // versionName restarts at 0.0; versionCode must still go up so installed builds can upgrade in place.
        versionCode = 3
        versionName = "0.0"
        testInstrumentationRunner = "com.schedulewidget.mobile.AuditInstrumentation"
        // On-device speech recognition (sherpa-onnx) ships native code: phones/tablets (arm64) and the emulator (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Local test APKs use the debug certificate. A production release never falls back to that certificate.
    val releaseStore = providers.environmentVariable("SCHEDULEWIDGET_KEYSTORE").orNull
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = requireNotNull(providers.environmentVariable("SCHEDULEWIDGET_STORE_PASSWORD").orNull) { "Release store password is missing" }
            keyAlias = requireNotNull(providers.environmentVariable("SCHEDULEWIDGET_KEY_ALIAS").orNull) { "Release key alias is missing" }
            keyPassword = requireNotNull(providers.environmentVariable("SCHEDULEWIDGET_KEY_PASSWORD").orNull) { "Release key password is missing" }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // update/ : Settings shows BuildConfig.VERSION_NAME.
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    // Offline speech-to-text, VAD and speaker diarization (k2-fsa/sherpa-onnx release AAR, Apache-2.0).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // .tar.bz2 model archives.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
}
