// app/src/main/java/com/gemweblive/SettingsDialog.kt
package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup // ADD THIS LINE for ViewGroup reference
import android.view.View // ADD THIS LINE for View reference
import android.widget.AdapterView // ADD THIS LINE for AdapterView reference
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.gemweblive.databinding.DialogSettingsBinding

import com.gemweblive.ApiVersion
import com.gemweblive.ApiKeyInfo


class SettingsDialog(
    context: Context,
    private val prefs: SharedPreferences,
    private val models: List<String>
) : Dialog(context) {

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
        setCancelable(false)

        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        loadApiVersionsFromResources()
        loadApiKeysFromResources()

        val currentModel = prefs.getString("selected_model", models.firstOrNull())
        selectedModel = models.firstOrNull { it == currentModel } ?: models.firstOrNull() ?: ""

        setupViews()
    }

    // ... (loadApiVersionsFromResources and loadApiKeysFromResources methods remain unchanged) ...

    private fun setupViews() {
        val currentVad = prefs.getInt("vad_sensitivity_ms", 800)
        binding.vadSensitivity.progress = currentVad
        binding.vadValue.text = "$currentVad ms"

        binding.vadSensitivity.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.vadValue.text = "$progress ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            // CORRECTED: Added 'override fun' for onStopTrackingTouch
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Setup Model Spinner
        binding.modelSpinnerSettings.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, models)
        selectedModel.let { initialModel ->
            val modelPosition = models.indexOf(initialModel)
            if (modelPosition != -1) {
                binding.modelSpinnerSettings.setSelection(modelPosition)
            }
        }
        // CORRECTED: Wrapped the listener in 'object : AdapterView.OnItemSelectedListener' and added 'override fun'
        binding.modelSpinnerSettings.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = models[position]
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
                putString("selected_model", selectedModel)
                
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
