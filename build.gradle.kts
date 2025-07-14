// Top of your build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20") // Add this line
    }
}
// settings.gradle.kts

pluginManagement {
    repositories {
        gradlePluginPortal() // For Gradle plugins themselves (like Kotlin Android)
        google()             // For Android-specific plugins and dependencies
        mavenCentral()       // General Maven repository
    }
    // Define versions for plugins used in app/build.gradle.kts
    resolutionStrategy {
        eachPlugin {
            // Apply version to org.jetbrains.kotlin.android
            if (requested.id.id == "org.jetbrains.kotlin.android") {
                useVersion("1.9.0") // Replace with your desired Kotlin version
            }
            // Add other plugin IDs and their versions here if needed
            // For example, for com.android.application:
            // if (requested.id.id == "com.android.application") {
            //     useVersion("8.1.0") // Or whatever AGP version you use
            // }
        }
    }
}

rootProject.name = "GemWebLive"
include(":app")
