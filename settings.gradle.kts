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
        maven { url = uri("https://jitpack.io") }
        maven("https://yuroyami.github.io/maven") {
            content { includeModuleByRegex("io\\.github\\.yuroyami", "libmpvkt.*") }
        }
    }
}

rootProject.name = "SmartMusicWatchParty"
include(":app")
