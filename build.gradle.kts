// build.gradle.kts (root project)

// This section defines dependencies for your build scripts themselves (like AGP)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        // No Kotlin plugin classpath needed here if you apply it via pluginManagement in settings.gradle.kts
    }
}

// This is where you might declare plugins that apply to the entire project (less common for Android)
plugins {
    // id("some.top.level.plugin") version "x.y.z"
}

// This block applies repositories to all sub-projects (like :app)
allprojects {
    repositories {
        google()
        mavenCentral()
        // Add other repositories your project needs for dependencies (e.g., JitPack)
    }
}

// Any other project-level configurations or tasks for the root project go here
