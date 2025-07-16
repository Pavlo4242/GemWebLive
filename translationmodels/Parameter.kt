// In: com/gemweblive/translationmodels/Parameter.kt

package com.gemweblive.translationmodels

import com.google.gson.annotations.SerializedName

/**
 * Represents the blueprint for any single configurable parameter for a model.
 *
 * This class is designed to be flexible by using nullable fields. An instance of this
 * class will only populate the fields that are relevant to the specific parameter
 * it represents, as defined in `inout.json`.
 *
 * For example:
 * - A 'temperature' parameter will have `range` and `default` populated.
 * - A 'topK' parameter will only have `fixed` populated.
 * - A 'responseMimeType' parameter will have `options` populated.
 *
 * The UI can inspect which fields are non-null to decide what kind of control
 * to render (e.g., a slider for `range`, a dropdown for `options`).
 */
data class Parameter(
    // For numeric parameters like temperature or topP, e.g., [0.0, 2.0]
    val range: List<Double>? = null,

    // The default value for the parameter, can be any type (String, Int, Double)
    val default: Any? = null,

    // For parameters with a single, non-configurable value, e.g., 64
    val fixed: Int? = null,

    // For parameters with a maximum value, e.g., 8192
    val max: Int? = null,

    // For parameters that have a list of string choices, e.g., ["text/plain", "application/json"]
    // We'll manually parse into this field.
    @SerializedName("options") // A custom name since the JSON key varies
    val options: List<String>? = null,

    // For complex, nested objects like safetySettings. We store it as a raw map.
    val nestedObject: Map<String, Any>? = null,

    // A simple description of the parameter's type, e.g., "ContentUnion"
    val type: String? = null
)
