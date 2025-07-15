// app/src/main/java/com/gemweblive/ApiModels.kt
package com.gemweblive

// Data class for API Versions (e.g., "v1alpha (Preview)" and "v1alpha")
data class ApiVersion(
    val displayName: String,
    val value: String
) {
    override fun toString(): String = displayName
}

// Data class for API Keys (e.g., "Language1a" and "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8")
data class ApiKeyInfo(
    val displayName: String,
    val value: String
) {
    override fun toString(): String = displayName
}

// --- NEW: Data class to define model capabilities ---
data class ModelInfo(
    val modelName: String,              // The technical name for the API call
    val displayName: String,            // The user-friendly name for the UI
    val supportsAudioInput: Boolean,
    val supportsAudioOutput: Boolean
) {
    // This override tells the ArrayAdapter in the Settings dialog how to display this object.
    override fun toString(): String = displayName
}
