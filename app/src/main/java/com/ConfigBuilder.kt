// Create a new file: util/ConfigBuilder.kt
package com.gemweblive.util

import com.gemweblive.ModelInfo
import com.gemweblive.SafetySetting
import com.google.gson.Gson

class ConfigBuilder(private val gson: Gson) {

    fun buildWebSocketConfig(modelInfo: ModelInfo, sessionHandle: String?): String {
        val setupConfig = mutableMapOf<String, Any>()

        setupConfig["model"] = "models/${modelInfo.modelName}"

        // --- Dynamically add parameters based on the ModelInfo blueprint ---

        if (modelInfo.supportsSystemInstruction) {
            // (Your existing logic to add system instructions)
            val instructionParts = getSystemInstructionParts()
            setupConfig["systemInstruction"] = mapOf("parts" to instructionParts)
        }

        if (modelInfo.inputType == com.gemweblive.InputType.AUDIO) {
            setupConfig["inputAudioTranscription"] = emptyMap<String, Any>()
        }

        if (modelInfo.outputType != com.gemweblive.OutputType.TEXT) {
            setupConfig["outputAudioTranscription"] = emptyMap<String, Any>()
        }
        
        if (modelInfo.supportsContextWindowCompression) {
             setupConfig["contextWindowCompression"] = mapOf("slidingWindow" to emptyMap<String, Any>())
        }

        modelInfo.supportedSafetySettings?.let {
            setupConfig["safetySettings"] = it.map { s ->
                mapOf("category" to s.category, "threshold" to s.threshold)
            }
        }
        
        modelInfo.defaultSpeechConfig?.let {
             setupConfig["speechConfig"] = mapOf(
                 "languageCode" to it.languageCode,
                 "voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to it.voiceName))
             )
        }

        val sessionResumption = sessionHandle?.let { mapOf("handle" to it) } ?: emptyMap()
        setupConfig["sessionResumption"] = sessionResumption

        return gson.toJson(mapOf("setup" to setupConfig))
    }
    
    // You would add another function here, e.g., buildRestConfig(), for non-live mode.

    private fun getSystemInstructionParts(): List<Map<String, String>> {
        // This would contain your full system instruction text, split into paragraphs
        val systemInstructionText = "### **LLM System Prompt... (your full prompt)"
        return systemInstructionText.split(Regex("\n\n+")).map { mapOf("text" to it.trim()) }
    }
}
