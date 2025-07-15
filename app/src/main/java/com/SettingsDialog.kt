package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.gemweblive.databinding.DialogSettingsBinding

// Ensure ApiVersion and ApiKeyInfo are imported from your ApiModels.kt
import com.gemweblive.ApiVersion
import com.gemweblive.ApiKeyInfo


class SettingsDialog(
    context: Context,
    private val prefs: SharedPreferences,
    private val models: List<String> // NEW: Constructor parameter for models
) : Dialog(context) {
    private lateinit var binding: DialogSettingsBinding

    // These lists will hold the parsed data from resources
    private var apiVersionsList: List<ApiVersion> = emptyList()
    private var apiKeysList: List<ApiKeyInfo> = emptyList()

    // These will hold the currently selected items
    private var selectedApiVersion: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null
    private var selectedModel: String = models.firstOrNull() ?: "" // NEW: Track selected model


    companion object {
        private const val TAG = "SettingsDialog"
    }

override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(false)

        // NEW: Set dialog window size
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        loadApiVersionsFromResources()
        loadApiKeysFromResources()

        // NEW: Load initial selected model from preferences
        val currentModel = prefs.getString("selected_model", models.firstOrNull())
        selectedModel = models.firstOrNull { it == currentModel } ?: models.firstOrNull() ?: ""


        setupViews()
    }

    // --- Corrected loadApiVersionsFromResources for SettingsDialog ---
    private fun loadApiVersionsFromResources() {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()

        for (itemString in rawApiVersions) {
            // Since your strings.xml for api_versions has NO delimiter (e.g., <item>v1alpha</item>),
            // we'll use the entire string for both display and value.
            parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersionsList = parsedList // Assign to SettingsDialog's property

        // Set initial selected version based on saved preference or first item
        val currentApiVersionValue = prefs.getString("api_version", null)
        selectedApiVersion = parsedList.firstOrNull { it.value == currentApiVersionValue } ?: parsedList.firstOrNull()

        // Ensure a selection if list is not empty
        if (selectedApiVersion == null && apiVersionsList.isNotEmpty()) {
            selectedApiVersion = apiVersionsList[0] // Fallback
        }
        Log.d(TAG, "loadApiVersions: Loaded ${apiVersionsList.size} items. Initial selected: ${selectedApiVersion?.value}")
    }

    // --- Corrected loadApiKeysFromResources for SettingsDialog ---
    private fun loadApiKeysFromResources() {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()

        for (itemString in rawApiKeys) {
            // CORRECTED: Split by colon (:) to match your strings.xml format for API keys
            val parts = itemString.split(":", limit = 2)

            if (parts.size == 2) {
                val displayName = parts[0].trim()
                val value = parts[1].trim()
                parsedList.add(ApiKeyInfo(displayName, value))
            } else {
                Log.e(TAG, "Malformed API key item in resources: '$itemString'. Expected 'DisplayName:Value' format.")
            }
        }
        apiKeysList = parsedList // Assign to SettingsDialog's property

        // Set initial selected API key based on saved preference or first item
        val currentApiKeyValue = prefs.getString("api_key", null)
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == currentApiKeyValue } ?: parsedList.firstOrNull()

        // Ensure a selection if list is not empty
        if (selectedApiKeyInfo == null && apiKeysList.isNotEmpty()) {
            selectedApiKeyInfo = apiKeysList[0] // Fallback
        }
        Log.d(TAG, "loadApiKeys: Loaded ${apiKeysList.size} items. Initial selected: ${selectedApiKeyInfo?.value?.take(5)}...")
    }

    // --- setupViews remains largely the same, but now uses correctly populated lists and selected items ---
 private fun setupViews() {
        val currentVad = prefs.getInt("vad_sensitivity_ms", 800)
        binding.vadSensitivity.progress = currentVad
        binding.vadValue.text = "$currentVad ms"

        binding.vadSensitivity.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.vadValue.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Setup Model Spinner (NEW)
        binding.modelSpinnerSettings.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, models)
        // Set initial selection for model spinner
        selectedModel.let { initialModel ->
            val modelPosition = models.indexOf(initialModel)
            if (modelPosition != -1) {
                binding.modelSpinnerSettings.setSelection(modelPosition)
            }
        }
        binding.modelSpinnerSettings.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = models[position] // Update selectedModel when an item is chosen
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        // Setup API Version Spinner
        binding.apiVersionSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersionsList)
        selectedApiVersion?.let { initialSelection ->
            val apiVersionPosition = apiVersionsList.indexOf(initialSelection)
            if (apiVersionPosition != -1) {
                binding.apiVersionSpinner.setSelection(apiVersionPosition)
            }
        }

        // Setup API Key Spinner
        binding.apiKeySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList)
        selectedApiKeyInfo?.let { initialSelection ->
            val apiKeyPosition = apiKeysList.indexOf(initialSelection)
            if (apiKeyPosition != -1) {
                binding.apiKeySpinner.setSelection(apiKeyPosition)
            }
        }

        binding.saveSettingsBtn.setOnClickListener {
            prefs.edit().apply {
                putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
                putString("selected_model", selectedModel) // NEW: Save selected model
                
                val selectedApiVersionFromSpinner = apiVersionsList[binding.apiVersionSpinner.selectedItemPosition]
                putString("api_version", selectedApiVersionFromSpinner.value)

                val selectedApiKeyFromSpinner = apiKeysList[binding.apiKeySpinner.selectedItemPosition]
                putString("api_key", selectedApiKeyFromSpinner.value)
                apply()
            }
            dismiss()
        }
    }
}
