// app/src/main/java/com/gemweblive/ApiModels.kt
package com.gemweblive

// Data class for API Versions
data class ApiVersion(
    val displayName: String,
    val value: String
) {
    override fun toString(): String {
        return displayName
    }
}

// Data class for API Keys
data class ApiKeyInfo(
    val displayName: String,
    val value: String
) {
    override fun toString(): String {
        return displayName
    }
}
