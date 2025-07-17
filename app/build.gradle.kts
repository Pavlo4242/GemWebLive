plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.20"
}

android {
    namespace = "com.BWCTrans"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.BWCTrans"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
           useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
         )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        // We are enabling compose AND viewBinding, as your project uses both.
        compose = true
        viewBinding = true
        dataBinding = true
    }
    composeOptions {
        // This version is compatible with Kotlin 1.9.22
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    // CORRECTED: Replaces the deprecated applicationVariants with the modern onVariants API
    // and correctly re-implements the output file renaming.
    onVariants { variant ->
        variant.outputs.all { output ->
            output.outputFileName = "BWCTrans-${variant.name}.apk"
        }
    }
}

dependencies {
    // Core & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose - Using a BOM compatible with the specified compiler
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose
