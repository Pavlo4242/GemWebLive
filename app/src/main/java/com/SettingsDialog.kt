package com.BWCTrans

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.BWCTrans.databinding.DialogSettingsBinding

class SettingsDialog(
    private val listener: DevSettingsListener,
    context: Context,
    private val prefs: SharedPreferences,
    private val models: List<String>
) : Dialog(context) {

    interface DevSettingsListener {
        fun onForceConnect()
    }

    private lateinit var binding: DialogSettingsBinding

    private var apiVersionsList: List<ApiVersion> = emptyList()
    private var apiKeysList: List<ApiKeyInfo> = emptyList()

    private var selectedApiVersion: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null
    private var selectedModel: String = models.firstOrNull() ?: ""

    companion object {
        private const val TAG = "SettingsDialog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(true) // Allow dismissing by clicking outside

        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        loadApiVersionsFromResources()
        loadApiKeysFromResources()

        selectedModel = prefs.getString("selected_model", models.firstOrNull()) ?: models.firstOrNull() ?: ""

        setupViews()
    }

    private fun loadApiVersionsFromResources() {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()

        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            if (parts.size == 2) {
                parsedList.add(ApiVersion(parts[0].trim(), parts[1].trim()))
            } else {
                Log.w(TAG, "API version item in resources: '$itemString' does not contain '|'. Using as DisplayName|Value.")
                parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
            }
        }
        apiVersionsList = parsedList

        val currentApiVersionValue = prefs.getString("api_version", null)
        selectedApiVersion = parsedList.firstOrNull { it.value == currentApiVersionValue } ?: parsedList.firstOrNull()
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
                Log.e(TAG, "Malformed API key item in resources: '$itemString'. Expected 'DisplayName:Value' format.")
            }
        }
        apiKeysList = parsedList
        val currentApiKeyValue = prefs.getString("api_key", null)
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == currentApiKeyValue } ?: parsedList.firstOrNull()
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

    // Model Spinner
    binding.modelSpinnerSettings.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, models)
    val modelPosition = models.indexOf(selectedModel)
    if (modelPosition != -1) {
        binding.modelSpinnerSettings.setSelection(modelPosition)
    }
    binding.modelSpinnerSettings.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            selectedModel = models[position]
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // API Version Spinner
    binding.apiVersionSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersionsList)
    selectedApiVersion?.let {
        val apiVersionPosition = apiVersionsList.indexOf(it)
        if (apiVersionPosition != -1) binding.apiVersionSpinner.setSelection(apiVersionPosition)
    }

    // API Key Spinner
    binding.apiKeySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList)
    selectedApiKeyInfo?.let {
        val apiKeyPosition = apiKeysList.indexOf(it)
        if (apiKeyPosition != -1) binding.apiKeySpinner.setSelection(apiKeyPosition)
    }

    // --- CORRECTED BUTTON LISTENERS ---

    // Save Button Listener
    binding.saveSettingsBtn.setOnClickListener {
        prefs.edit().apply {
            putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
            putString("selected_model", selectedModel)

            if (binding.apiVersionSpinner.selectedItemPosition >= 0) {
                val selectedApiVersionFromSpinner = apiVersionsList[binding.apiVersionSpinner.selectedItemPosition]
                putString("api_version", selectedApiVersionFromSpinner.value)
            }

            if (binding.apiKeySpinner.selectedItemPosition >= 0) {
                val selectedApiKeyFromSpinner = apiKeysList[binding.apiKeySpinner.selectedItemPosition]
                putString("api_key", selectedApiKeyFromSpinner.value)
            }
            apply()
        }
        dismiss()
    }

    // Force Connect Button Listener (now at the correct level)
    binding.forceConnectBtn.setOnClickListener {
        listener.onForceConnect()
        dismiss() // Close the dialog
    }
}
