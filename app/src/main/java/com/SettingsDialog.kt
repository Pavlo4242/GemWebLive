// app/src/main/java/com/gemweblive/SettingsDialog.kt
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
        loadApiVersionsFromResources()
        loadApiKeysFromResources()

        setupViews()
    }

    // Loads and parses the API versions from resources
    private fun loadApiVersionsFromResources() { // Or loadApiVersionsFromResources(context: Context) if it takes context
        // Use 'this' for context if inside SettingsDialog or MainActivity, or the passed 'context' param
        val currentContext = if (this is Context) this else context // Adjust this line based on your function signature
        val rawApiVersions = currentContext.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
    
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2) // <--- NEW: Split by pipe
    
            if (parts.size == 2) {
                val displayName = parts[0].trim()
                val value = parts[1].trim()
                parsedList.add(ApiVersion(displayName, value)) // <--- Correctly use separated parts
            } else {
                // Handle cases where the format might just be "v1alpha" without a pipe,
                // or if it's malformed. If your XML always uses "Display|Value",
                // this else block indicates an error.
                Log.e(TAG, "Malformed API version item in resources: '$itemString'. Expected 'DisplayName|Value' format.")
                // If you intend to allow simple "v1alpha" entries, you might do:
                // parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
            }
        }
        apiVersionsList = parsedList 

        // Set initial selected version based on saved preference or first item
        val currentApiVersionValue = prefs.getString("api_version", null)
        selectedApiVersion = apiVersionsList.firstOrNull { it.value == currentApiVersionValue } ?: apiVersionsList.firstOrNull()
        // Fallback if list is empty (though your array.xml implies it won't be)
        if (selectedApiVersion == null && apiVersionsList.isNotEmpty()) {
            selectedApiVersion = apiVersionsList[0]
        }
    }

    // Loads and parses the API keys from resources
    private fun loadApiKeysFromResources() {
        // CORRECTED: R.array.keys to R.array.api_keys
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys) // Use R.array.api_keys
        val parsedList = mutableListOf<ApiKeyInfo>()

        for (itemString in rawApiKeys) {
            // Assuming format: "Display Name:Value"
            val parts = itemString.split(":", limit = 2) // Split by colon

            if (parts.size == 2) {
                parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim())) // Create ApiKeyInfo
            } else {
                Log.e(TAG, "Malformed API key item in arrays.xml: '$itemString'. Expected 'DisplayName:Value' format.")
            }
        }
        apiKeysList = parsedList

        // Set initial selected API key based on saved preference or first item
        val currentApiKeyValue = prefs.getString("api_key", null)
        selectedApiKeyInfo = apiKeysList.firstOrNull { it.value == currentApiKeyValue } ?: apiKeysList.firstOrNull()
        // Fallback if list is empty
        if (selectedApiKeyInfo == null && apiKeysList.isNotEmpty()) {
            selectedApiKeyInfo = apiKeysList[0]
        }
    }


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
        selectedApiVersion?.let {
            val apiVersionPosition = apiVersionsList.indexOf(it)
            if (apiVersionPosition != -1) {
                binding.apiVersionSpinner.setSelection(apiVersionPosition)
            }
        }

        // Setup API Key Spinner
        binding.apiKeySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList)
        // Set spinner selection
        selectedApiKeyInfo?.let {
            val apiKeyPosition = apiKeysList.indexOf(it)
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
