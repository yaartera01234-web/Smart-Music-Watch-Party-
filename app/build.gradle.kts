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
        versionCode = 39
        versionName = "39"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // Stable signing: CI debug builds must share one key, otherwise every new APK refuses
        // to install over the previous one (signature mismatch).
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")

    // v25: native player (Media3) — video/audio native decode + background + notification
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // v28: HLS (.m3u8) + DASH (.mpd) native support — pehle ye missing tha,
    // is liye direct HLS links native player me nahi chalte the
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
}
