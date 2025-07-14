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


class SettingsDialog(context: Context, private val prefs: SharedPreferences) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding

    // These lists will hold the parsed data from resources
    private var apiVersionsList: List<ApiVersion> = emptyList()
    private var apiKeysList: List<ApiKeyInfo> = emptyList()

    // These will hold the currently selected items
    private var selectedApiVersion: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null

    companion object {
        private const val TAG = "SettingsDialog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(false)

        // Load data from resources into our lists
        loadApiVersionsFromResources() // No context parameter needed here, Dialog has it
        loadApiKeysFromResources()     // No context parameter needed here

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
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Setup API Version Spinner
        binding.apiVersionSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersionsList)
        // Set spinner selection
        selectedApiVersion?.let { initialSelection ->
            val apiVersionPosition = apiVersionsList.indexOf(initialSelection)
            if (apiVersionPosition != -1) {
                binding.apiVersionSpinner.setSelection(apiVersionPosition)
            }
        }

        // Setup API Key Spinner
        binding.apiKeySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList)
        // Set spinner selection
        selectedApiKeyInfo?.let { initialSelection ->
            val apiKeyPosition = apiKeysList.indexOf(initialSelection)
            if (apiKeyPosition != -1) {
                binding.apiKeySpinner.setSelection(apiKeyPosition)
            }
        }

        binding.saveSettingsBtn.setOnClickListener {
            prefs.edit().apply {
                putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
                // Save the actual 'value' of the selected ApiVersion object
                val selectedApiVersionFromSpinner = apiVersionsList[binding.apiVersionSpinner.selectedItemPosition]
                putString("api_version", selectedApiVersionFromSpinner.value)

                // Save the actual 'value' of the selected ApiKeyInfo object
                val selectedApiKeyFromSpinner = apiKeysList[binding.apiKeySpinner.selectedItemPosition]
                putString("api_key", selectedApiKeyFromSpinner.value)
                apply()
            }
            dismiss()
        }
    }
}
