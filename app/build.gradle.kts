plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.party.music"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.party.music"
        minSdk = 24
        targetSdk = 35
        versionCode = 50
        versionName = "50-MPV-Hybrid-Final"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // splits removed - ndk abiFilters handles arm64 only (conflict fix)
    buildTypes {
        release { isMinifyEnabled = false }
        debug {
            signingConfig = signingConfigs.create("party") {
                storeFile = file("${rootDir}/keystore/party.jks")
                storePassword = "party123"
                keyAlias = "party"
                keyPassword = "party123"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // MPV 0.23.0 libmpvKt - pure native player
    implementation("com.github.yuroyami:libmpvKt:0.23.0")
    // YouTube 360p lock - NewPipeExtractor
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
