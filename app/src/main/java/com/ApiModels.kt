// app/src/main/java/com/gemweblive/ApiModels.kt
package com.gemweblive

// Enums to define capabilities clearly
enum class InputType { AUDIO, TEXT }
enum class OutputType { AUDIO, TEXT, AUDIO_AND_TEXT }

// Data class for a single safety setting
data class SafetySetting(val category: String, val threshold: String)

/**
 * The ModelInfo class is the single source of truth for a model's capabilities.
 */
data class ModelInfo(
    val modelName: String,
    val displayName: String,
    val inputType: InputType,
    val outputType: OutputType,
    val isLiveModel: Boolean,
    val supportsSystemInstruction: Boolean = false,
    val supportsThinkingConfig: Boolean = false,
    val supportsSafetySettings: Boolean = true,
    val supportsInputAudioTranscription: Boolean = false,
    val supportsOutputAudioTranscription: Boolean = false,
    val supportsContextWindowCompression: Boolean = false,
    val supportsAffectiveDialog: Boolean = false,
    val supportsProactivity: Boolean = false
) {
    override fun toString(): String = displayName
}

// Data class for API Versions
data class ApiVersion(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}

// Data class for API Keys
data class ApiKeyInfo(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}
