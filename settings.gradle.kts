pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube -> direct audio stream)
        maven { url = uri("https://jitpack.io") }
        // libmpvKt (MPV core) — Maven Central par nahi hai, apne GitHub Pages par hai
        maven("https://yuroyami.github.io/maven") {
            content { includeModuleByRegex("io\\.github\\.yuroyami", "libmpvkt.*") }
        }
    }
}

rootProject.name = "SmartWatchPartyPro"
include(":app")
