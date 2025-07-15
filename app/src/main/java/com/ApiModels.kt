// app/src/main/java/com/gemweblive/ApiModels.kt
package com.gemweblive

// Enums to define capabilities clearly
enum class InputType { AUDIO, TEXT }
enum class OutputType { AUDIO, TEXT, AUDIO_AND_TEXT }

// Data class for a single safety setting
data class SafetySetting(val category: String, val threshold: String)

/**
 * The ModelInfo class is the single source of truth for a model's capabilities.
 * It provides the "blueprint" that the ConfigBuilder uses to construct valid requests.
 */
data class ModelInfo(
    val modelName: String,
    val displayName: String,
    val inputType: InputType,
    val outputType: OutputType,

    // --- Capability Flags ---
    val isLiveModel: Boolean,
    val supportsSystemInstruction: Boolean = false,
    val supportsThinkingConfig: Boolean = false,
    val supportsSafetySettings: Boolean = true, // Universal as requested
    val supportsInputAudioTranscription: Boolean = false,
    val supportsOutputAudioTranscription: Boolean = false,
    val supportsContextWindowCompression: Boolean = false,
    val supportsAffectiveDialog: Boolean = false,
    val supportsProactivity: Boolean = false
) {
    // This override is crucial for displaying the name in the Spinner
    override fun toString(): String = displayName
}

// These are now defined only once.
data class ApiVersion(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}
data class ApiKeyInfo(val displayName: String, val value: String) {
    override fun toString(): String = displayName
}
