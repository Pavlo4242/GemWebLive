//INCOMPLETE//
// In: com/gemweblive/translationmodels/Configurator.kt

package com.gemweblive.translationmodels

import com.google.gson.Gson

class Configurator {

    private val gson = Gson()

    /**
     * Creates the final JSON payload to be sent to the API.
     *
     * @param model The selected Model object.
     * @param userSettings A map of settings the user has chosen in the UI, e.g., {"temperature": 1.5, "maxOutputTokens": 1024}.
     * @return A JSON String ready to be sent as the request body.
     */
    fun buildApiConfig(model: Model, userSettings: Map<String, Any>): String {
        val generationConfig = mutableMapOf<String, Any>()

        // 1. Iterate through the model's ALLOWED parameters
        for ((paramName, paramDetails) in model.parameters) {

            // 2. Check if the user has provided a value for this parameter
            if (userSettings.containsKey(paramName)) {
                generationConfig[paramName] = userSettings.getValue(paramName)
            }
            // 3. If not, check if there's a default value we should use
            else if (paramDetails.default != null) {
                generationConfig[paramName] = paramDetails.default
            }
            // (If neither, we don't include it unless it's mandatory)
        }

        // 4. Handle non-configurable, but required, parameters
        // These are added by the configurator, not the user.
        val safetySettings = mapOf(
            "safetySettings" to listOf(
                mapOf("category" to "HARM_CATEGORY_HARASSMENT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_HATE_SPEECH", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold" to "BLOCK_NONE"),
                mapOf("category" to "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold" to "BLOCK_NONE")
            )
        )

        // 5. Construct the final request body
        val finalPayload = mapOf(
            "model" to "models/${model.code}", // Always mandatory
            "generationConfig" to generationConfig,
            // Add other top-level params like safetySettings if they apply
        )

        return gson.toJson(finalPayload)
    }
}
