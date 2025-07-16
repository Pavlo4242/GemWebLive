//INCOMPLETE//
// In: com/gemweblive/translationmodels/Configurator.kt

package com.gemweblive.translationmodels

import com.google.gson.Gson

class Configurator {

    private val gson = Gson()

    fun buildApiConfig(model: Model, userSettings: Map<String, Any>): String {
        val payload = mutableMapOf<String, Any>()
        val generationConfig = mutableMapOf<String, Any>()

        // Start with the user's settings, but only include ones valid for this model
        for ((key, value) in userSettings) {
            if (model.parameters.containsKey(key)) {
                generationConfig[key] = value
            }
        }

        // --- MANDATORY & CONDITIONAL LOGIC ---

        // Rule: 'safetySettings' are mandatory for the API, but not set by the user.
        // We add them here with a safe, hardcoded default.
        payload["safetySettings"] = listOf(
            mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_NONE"),
            mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_NONE"),
            mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_NONE"),
            mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_NONE")
        )

        // Rule: 'systemInstruction' is critical for some models.
        // Let's add it if the model supports it (as determined by its parameters).
        if (model.parameters.containsKey("systemInstruction")) {
             // You would get your actual system prompt from a helper function
            payload["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to "You are a helpful translator.")))
        }
        
        // Rule: 'responseModalities' is conditional for audio output models.
        if (model.outputs.contains("audio")) {
            generationConfig["responseModalities"] = listOf("AUDIO", "TEXT")
        }


        // Final Assembly
        payload["model"] = "models/${model.code}"
        payload["generationConfig"] = generationConfig

        return gson.toJson(payload)
    }
}
