// build.gradle.kts (root project)

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Keep the Android Gradle Plugin classpath here
        classpath("com.android.tools.build:gradle:8.2.2")
        // REMOVE this line: classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

// Ensure you have a 'plugins' block here if you apply any top-level plugins (e.g., JVM, version catalog)
// plugins {
//     // id("your.top.level.plugin") version "x.y.z"
// }

allprojects {
    repositories {
        google()
        mavenCentral()
        // Add other repositories your project needs for dependencies
    }
}

// Other project-level configurations go here (e.g., tasks, ext properties)
rootProject.name = "GemWebLive"
include(":app")
