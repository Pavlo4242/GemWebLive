package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.gemweblive.databinding.DialogSettingsBinding
import com.gemweblive.ApiVersion
import com.gemweblive.ApiKeyInfo


class SettingsDialog(context: Context, private val prefs: SharedPreferences) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding

    // Use a nullable var to store the list after parsing from resources
    private var apiVersionsList: List<ApiVersion> = emptyList()
    private var apiKeysList: List<ApiKeyInfo> = emptyList()

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

        // Load data from resources first
        loadApiVersionsFromResources()
        loadApiKeysFromResources()

        setupViews()
    }

   private fun loadApiKeysFromResources() {
        // CORRECTED: R.array.keys to R.array.api_keys
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiVersions) {
            // Assuming api_versions in arrays.xml is just the 'value' (e.g., "v1alpha")
            // If you want to use "Display|Value" format in arrays.xml for API versions too,
            // you'd parse them similarly to apiKeys below.
            // For now, assuming direct value and display same as value for simplicity.
            parsedList.add(ApiVersion(itemString, itemString))
        }
        apiVersionsList = parsedList
        // Set initial selected version based on saved preference or default
        val currentApiVersionValue = prefs.getString("api_version", apiVersionsList.firstOrNull()?.value)
        selectedApiVersion = apiVersionsList.firstOrNull { it.value == currentApiVersionValue } ?: apiVersionsList.firstOrNull()
        if (selectedApiVersion == null && apiVersionsList.isNotEmpty()) {
            selectedApiVersion = apiVersionsList[0] // Fallback to first if preference not found or list is empty
        }
    }


    private fun loadApiKeysFromResources() {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()

        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            
            if (parts.size == 2) {
                val displayName = parts[0].trim()
                val value = parts[1].trim()
                parsedList.add(ApiKeyInfo(displayName, value))
            } else {
                Log.e(TAG, "Malformed API key item in arrays.xml: '$itemString'. Expected 'DisplayName:Value' format.")
            }
        }
        apiKeysList = parsedList

        // Set initial selected API key based on saved preference or default
        val currentApiKeyValue = prefs.getString("api_key", apiKeysList.firstOrNull()?.value) // Corrected key name to "api_key"
        selectedApiKeyInfo = apiKeysList.firstOrNull { it.value == currentApiKeyValue } ?: apiKeysList.firstOrNull()
        if (selectedApiKeyInfo == null && apiKeysList.isNotEmpty()) {
            selectedApiKeyInfo = apiKeysList[0] // Fallback to first if preference not found or list is empty
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
        val apiVersionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersionsList) // Use parsed list
        apiVersionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.apiVersionSpinner.adapter = apiVersionAdapter

        // Set spinner selection
        selectedApiVersion?.let {
            val apiVersionPosition = apiVersionsList.indexOf(it)
            if (apiVersionPosition != -1) {
                binding.apiVersionSpinner.setSelection(apiVersionPosition)
            }
        }

        // Setup API Key Spinner
        val apiKeyAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList) // Use parsed list
        apiKeyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.apiKeySpinner.adapter = apiKeyAdapter

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
                putString("api_key", selectedApiKeyFromSpinner.value) // Corrected key name to "api_key"
                apply()
            }
            dismiss()
        }
    }
}
