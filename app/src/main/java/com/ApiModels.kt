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

enum class OutputType {
    AUDIO_ONLY,
    TEXT_ONLY,
    AUDIO_AND_TEXT, // For models that provide both simultaneously
    USER_CHOICE     // For the special case model
}

data class ModelInfo(
    val modelName: String,
    val displayName: String,
    val supportsAudioInput: Boolean,
    val outputType: OutputType // Use the new enum
) {
    // This override tells the ArrayAdapter in the Settings dialog how to display this object.
    override fun toString(): String = displayName
}
