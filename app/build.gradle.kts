plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.party.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.party.music"      // Original app update, same package and signing key
        minSdk = 24
        targetSdk = 35
        versionCode = 125                      // Keyboard GIF candidate; original package/signature
        versionName = "117-GIF-TEST1"
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
        jniLibs.useLegacyPackaging = true
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
    implementation("io.github.yuroyami:libmpvkt:0.3.0")
    // YouTube VIDEO ke liye — MpvView (surface wala view). Isi ke through video page ke peeche dikhti hai.
    implementation("io.github.yuroyami:libmpvkt-view:0.3.0")
    // YouTube ke liye asli audio stream URL (MPV ko YouTube ka page nahi, seedha stream milta hai)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
