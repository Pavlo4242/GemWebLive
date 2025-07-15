// app/src/main/java/com/gemweblive/util/ConfigBuilder.kt
package com.gemweblive.util

import com.gemweblive.ApiModels.InputType
import com.gemweblive.ApiModels.ModelInfo
import com.gemweblive.ApiModels.OutputType
import com.gemweblive.ApiModels.SafetySetting
import com.google.gson.Gson

class ConfigBuilder(private val gson: Gson) {

    fun buildWebSocketConfig(modelInfo: ModelInfo, sessionHandle: String?): String {
        val setupConfig = mutableMapOf<String, Any>()

        setupConfig["model"] = "models/${modelInfo.modelName}"

        if (modelInfo.supportsSafetySettings) {
            setupConfig["safetySettings"] = getDefaultSafetySettings().map { s ->
                mapOf("category" to s.category, "threshold" to s.threshold)
            }
        }
        if (modelInfo.supportsThinkingConfig) {
            setupConfig["thinkingConfig"] = mapOf("thinkingBudget" to -1)
        }
        if (modelInfo.supportsSystemInstruction) {
            setupConfig["systemInstruction"] = mapOf("parts" to getSystemInstructionParts())
        }

        if (modelInfo.isLiveModel) {
            if (modelInfo.supportsInputAudioTranscription) {
                setupConfig["inputAudioTranscription"] = emptyMap<String, Any>()
            }
            if (modelInfo.supportsOutputAudioTranscription) {
                setupConfig["outputAudioTranscription"] = emptyMap<String, Any>()
            }
            if (modelInfo.supportsContextWindowCompression) {
                setupConfig["contextWindowCompression"] = mapOf("slidingWindow" to emptyMap<String, Any>())
            }
        }
        if (modelInfo.modelName.contains("native", ignoreCase = true)) {
            val generationConfig = setupConfig.getOrPut("generationConfig") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
            if (modelInfo.supportsAffectiveDialog) {
                generationConfig["enable_affective_dialog"] = true
            }
            if (modelInfo.supportsProactivity) {
                generationConfig["proactivity"] = mapOf("proactiveAudio" to true)
            }
        }

        val sessionResumption = sessionHandle?.let { mapOf("handle" to it) } ?: emptyMap()
        setupConfig["sessionResumption"] = sessionResumption

        return gson.toJson(mapOf("setup" to setupConfig))
    }

    private fun getDefaultSafetySettings(): List<SafetySetting> {
        return listOf(
            SafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE")
        )
    }

    private fun getSystemInstructionParts(): List<Map<String, String>> {
        val systemInstructionText = "### **LLM System Prompt..." // Your full prompt text
        return systemInstructionText.split(Regex("\n\n+")).map { mapOf("text" to it.trim()) }
    }
}
