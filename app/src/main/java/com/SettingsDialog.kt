package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.gemweblive.databinding.DialogSettingsBinding

class SettingsDialog(
    context: Context,
    private val prefs: SharedPreferences,
    private val models: List<ModelInfo> // Ensure this is List<ModelInfo>
) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding
    private var apiVersionsList: List<ApiVersion> = emptyList()
    private var apiKeysList: List<ApiKeyInfo> = emptyList()

    companion object {
        private const val TAG = "SettingsDialog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        loadApiVersionsFromResources()
        loadApiKeysFromResources()
        setupViews()
    }

    private fun loadApiVersionsFromResources() {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            parsedList.add(if (parts.size == 2) ApiVersion(parts[0].trim(), parts[1].trim()) else ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersionsList = parsedList
    }

    private fun loadApiKeysFromResources() {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim())) else Log.e(TAG, "Malformed API key: $itemString")
        }
        apiKeysList = parsedList
    }

    private fun setupViews() {
        // VAD SeekBar
        val currentVad = prefs.getInt("vad_sensitivity_ms", 800)
        binding.vadSensitivity.progress = currentVad
        binding.vadValue.text = "$currentVad ms"
        binding.vadSensitivity.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { binding.vadValue.text = "$progress ms" }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Model Spinner
        binding.modelSpinnerSettings.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, models)
        val currentModelName = prefs.getString("selected_model", null)
        val modelPosition = models.indexOfFirst { it.modelName == currentModelName }
        if (modelPosition != -1) binding.modelSpinnerSettings.setSelection(modelPosition)

        // API Version Spinner
        binding.apiVersionSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersionsList)
        val currentApiVersionValue = prefs.getString("api_version", null)
        val apiVersionPosition = apiVersionsList.indexOfFirst { it.value == currentApiVersionValue }
        if (apiVersionPosition != -1) binding.apiVersionSpinner.setSelection(apiVersionPosition)

        // API Key Spinner
        binding.apiKeySpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiKeysList)
        val currentApiKeyValue = prefs.getString("api_key", null)
        val apiKeyPosition = apiKeysList.indexOfFirst { it.value == currentApiKeyValue }
        if (apiKeyPosition != -1) binding.apiKeySpinner.setSelection(apiKeyPosition)

        // Save Button
        binding.saveSettingsBtn.setOnClickListener {
            prefs.edit().apply {
                putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
                putString("selected_model", (binding.modelSpinnerSettings.selectedItem as ModelInfo).modelName)
                putString("api_version", (binding.apiVersionSpinner.selectedItem as ApiVersion).value)
                putString("api_key", (binding.apiKeySpinner.selectedItem as ApiKeyInfo).value)
                apply()
            }
            dismiss()
        }
    }
}
