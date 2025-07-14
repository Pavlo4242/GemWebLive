// Top of your build.gradle.kts
buildscript {
    repositories {
        google()  // This is REQUIRED for Android plugins
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2") // Use stable version
    }
}

plugins {
    id("com.android.application") version "8.2.2" apply false
}
