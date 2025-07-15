// app/src/main/java/com/gemweblive/ApiModels.kt
package com.gemweblive

// Data class for API Versions (e.g., "v1alpha (Preview)" and "v1alpha")
data class ApiVersion(
    val displayName: String, // The string to display in UI (e.g., "v1alpha (Preview)")
    val value: String        // The actual API version string (e.g., "v1alpha")
) {
    // This override tells ArrayAdapter how to display this object in a Spinner
    override fun toString(): String {
        return displayName
    }
}

// Data class for API Keys (e.g., "Language1a" and "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8")
data class ApiKeyInfo(
    val displayName: String, // The string to display in UI (e.g., "Language1a")
    val value: String        // The actual API key string (e.g., "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8")
) {
    // This override tells ArrayAdapter how to display this object in a Spinner
    override fun toString(): String {
        return displayName
    }
}
