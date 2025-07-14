pluginManagement {
    repositories {
        google()
        mavenCentral() // Add this
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.application") version "8.13.0" apply false
    // Other plugins...
}
