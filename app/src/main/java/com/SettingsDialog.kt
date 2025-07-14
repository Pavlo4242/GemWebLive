package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.gemweblive.databinding.DialogSettingsBinding

class SettingsDialog(context: Context, private val prefs: SharedPreferences) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding

    private val apiVersions = listOf("v1alpha", "v1", "v1beta", "v1beta1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setCancelable(false)

        setupViews()
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
        val apiVersionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, apiVersions)
        apiVersionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.apiVersionSpinner.adapter = apiVersionAdapter

        val currentApiVersion = prefs.getString("api_version", apiVersions[0]) ?: apiVersions[0]
        val apiVersionPosition = apiVersions.indexOf(currentApiVersion)
        binding.apiVersionSpinner.setSelection(apiVersionPosition)

        binding.saveSettingsBtn.setOnClickListener {
            prefs.edit().apply {
                putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
                putString("api_version", apiVersions[binding.apiVersionSpinner.selectedItemPosition]) // Save selected API version
                apply()
            }
            dismiss()
        }
    }
}
