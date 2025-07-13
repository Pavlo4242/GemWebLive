package com.gemweblive

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import com.example.gemweblive.databinding.DialogSettingsBinding

class SettingsDialog(context: Context, private val prefs: SharedPreferences) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding

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

        binding.saveSettingsBtn.setOnClickListener {
            prefs.edit().apply {
                putInt("vad_sensitivity_ms", binding.vadSensitivity.progress)
                apply()
            }
            dismiss()
        }
    }
}
