// In: com/gemweblive/translationmodels/Model.kt

package com.gemweblive.translationmodels

import com.google.gson.annotations.SerializedName

/**
 * Represents a single translation model, enriched with its specific configuration parameters.
 * This class is designed to be created by parsing the `inout.json` file.
 *
 * @property name The user-friendly display name of the model, e.g., "Gemini 2.5 Pro".
 * @property code The specific identifier for the model used in API calls, e.g., "gemini-2.5-pro".
 * @property inputs A list of supported input modalities, such as "audio", "text", or "images".
 * @property outputs A list of supported output modalities, such as "text" or "audio".
 * @property liveApi A boolean flag indicating if the model supports real-time, streaming interactions.
 * @property parameters A comprehensive list of `Parameter` objects that can be used to configure this model's API requests.
 */
data class Model(
    @SerializedName("name")
    val name: String,

    @SerializedName("code")
    val code: String,

    // The 'inputs' and 'outputs' fields are defined at the group level in the JSON.
    // They will be assigned to each model during the parsing process.
    val inputs: List<String>,
    val outputs: List<String>,

    @SerializedName("live_api")
    val liveApi: Boolean,

    @SerializedName("parameters")
    val parameters: Map<String, Parameter>
)

/**
 * Represents a single configurable parameter for a model.
 * The structure is generic to accommodate the varied parameter formats in `inout.json`.
 *
 * @property range A list of two numbers representing the minimum and maximum valid values for a numeric parameter. Can also be a descriptive string.
 * @property default The default value for the parameter.
 * @property fixed A single, non-configurable value for a parameter.
 * @property max The maximum allowed value.
 * @property type A string describing the parameter's data type, e.g., "ContentUnion".
 * @property harmBlockThreshold A list of possible values for the harm block threshold setting.
 * @property harmCategory A list of possible values for the harm category setting.
 * @property onOff A list of values for toggling a feature.
 * @property budget A map describing the budget for a feature.
 */
data class Parameter(
    // Numeric range, e.g., [0.0, 2.0] or a descriptive string array
    val range: List<Any>? = null,

    // Default value, can be any type
    val default: Any? = null,

    // Fixed value, can be any type
    val fixed: Any? = null,

    // Max value
    val max: Int? = null,

    // Type description string
    val type: String? = null,

    // Specific to Safety Settings
    @SerializedName("HarmBlockThreshold")
    val harmBlockThreshold: List<String>? = null,

    @SerializedName("HarmCategory")
    val harmCategory: List<String>? = null,

    // Specific to Thinking Config
    @SerializedName("on_off")
    val onOff: List<Int>? = null,
    val budget: Map<String, String>? = null
)
