plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

configurations.configureEach {
    exclude(group = "io.github.yuroyami", module = "libmpvkt-native")
}

android {
    namespace = "app.party.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.party.music"      // ORIGINAL app ka package (V111/v48 users ke upar update)
        minSdk = 24
        targetSdk = 35
        versionCode = 113                      // installed app 48 hai -> update install ho jayega
        versionName = "113-OLD-MPV"
        manifestPlaceholders["appLabel"] = "Music Watch Party"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // Debug builds bhi isi key se sign hon (warna har naya APK purane ke upar install nahi hota)
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
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

    // Purana native player (ExoPlayer) — jaisa tha waisa, chheda nahi gaya
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ══════════ v111-FIX: LOCK SCREEN / BACKGROUND AUDIO ENGINE ══════════
    // MPV core (vid=no => audio only, surface ki zarurat nahi => lock screen par bhi chalta hai)
    // v113: Maven wala native module band (us me naya FFmpeg hai) — uski jagah app/libs ka
    // custom AAR use hota hai jis me Synkplay 0.23.0 wali PURANI libs (mpv + FFmpeg v62) hain.
    implementation("io.github.yuroyami:libmpvkt:0.3.0")
    implementation(files("libs/libmpvkt-native-compat.aar"))
    // YouTube ke liye asli audio stream URL (MPV ko YouTube ka page nahi, seedha stream milta hai)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
