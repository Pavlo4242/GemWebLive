// SettingsDialog.java
package com.gemweblive;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import com.gemweblive.databinding.DialogSettingsBinding;
import java.util.Arrays;
import java.util.List;

public class SettingsDialog extends Dialog {

    private DialogSettingsBinding binding;
    private final SharedPreferences prefs;
    private final List<String> apiVersions = Arrays.asList("v1alpha", "v1", "v1beta", "v1beta1");

    public SettingsDialog(@NonNull Context context, SharedPreferences prefs) {
        super(context);
        this.prefs = prefs;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DialogSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setCancelable(false);

        setupViews();
    }

    private void setupViews() {
        // VAD Sensitivity SeekBar
        int currentVad = prefs.getInt("vad_sensitivity_ms", 800);
        binding.vadSensitivity.setProgress(currentVad);
        binding.vadValue.setText(String.format("%d ms", currentVad));

        binding.vadSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                binding.vadValue.setText(String.format("%d ms", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // API Version Spinner
        ArrayAdapter<String> apiVersionAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, apiVersions);
        apiVersionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.apiVersionSpinner.setAdapter(apiVersionAdapter);

        String currentApiVersion = prefs.getString("api_version", apiVersions.get(0));
        int apiVersionPosition = apiVersions.indexOf(currentApiVersion);
        binding.apiVersionSpinner.setSelection(apiVersionPosition);

        // Save Button
        binding.saveSettingsBtn.setOnClickListener(v -> {
            prefs.edit()
                    .putInt("vad_sensitivity_ms", binding.vadSensitivity.getProgress())
                    .putString("api_version", apiVersions.get(binding.apiVersionSpinner.getSelectedItemPosition()))
                    .apply();
            dismiss();
        });
    }
}
