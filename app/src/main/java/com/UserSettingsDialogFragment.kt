// Create a new Kotlin file for this class
package com.gemweblive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.gemweblive.databinding.DialogSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class UserSettingsDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup listeners for the new controls
        binding.closeBtn.setOnClickListener {
            dismiss()
        }

        // Example for switch - you'd save this to SharedPreferences
        binding.autoPlaybackSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Save the value, e.g., to SharedPreferences
            // prefs.edit().putBoolean("auto_playback", isChecked).apply()
        }
        
        // Example for seekbar
        // binding.textSizeSeekBar.setOnSeekBarChangeListener(...)
        
        // binding.sendFeedbackBtn.setOnClickListener { ... }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
