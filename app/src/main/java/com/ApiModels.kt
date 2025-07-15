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

enum class InputType { AUDIO, TEXT }
enum class OutputType { AUDIO, TEXT, AUDIO_AND_TEXT } // AUDIO_AND_TEXT for models that return both streams

// --- Data classes for specific configuration blocks ---
data class SafetySetting(val category: String, val threshold: String)
data class SpeechConfig(val languageCode: String, val voiceName: String)

// --- The new, more powerful ModelInfo ---
data class ModelInfo(
    val modelName: String,
    val displayName: String,
    val inputType: InputType,
    val outputType: OutputType,

    // --- Capability Flags ---
    val supportsSystemInstruction: Boolean = false,
    val supportsThinkingConfig: Boolean = false, // Universal
    val supportsSafetySettings: Boolean = true,  // Universal as per user request

    // --- Live API / WebSocket Specific Flags ---
    val isLiveModel: Boolean, // A flag to distinguish WebSocket-capable models
    val supportsInputAudioTranscription: Boolean = false,
    val supportsOutputAudioTranscription: Boolean = false,
    val supportsContextWindowCompression: Boolean = false,

    // --- Native Model Specific Flags ---
    val supportsAffectiveDialog: Boolean = false,
    val supportsProactivity: Boolean = false
) {
    override fun toString(): String = displayName
}

// Other data classes (ApiVersion, ApiKeyInfo) remain the same...
data class ApiVersion(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}
data class ApiKeyInfo(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}
