plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.party.music"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.smart.mpv.party"
        minSdk = 24
        targetSdk = 35
        versionCode = 58
        versionName = "58-CRASH-FIXED-PURE-NATIVE"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
    implementation("io.github.yuroyami:libmpvkt-view:0.3.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
