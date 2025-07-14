// MainActivity.java
package com.gemweblive;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.gemweblive.databinding.ActivityMainBinding;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private ActivityMainBinding binding;
    private AudioHandler audioHandler;
    private WebSocketClient webSocketClient;
    private TranslationAdapter translationAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isListening = false;
	private volatile boolean isSessionActive = false;
	private volatile boolean isServerReady = false;

    private final List<String> models = Arrays.asList(
        "gemini-live-2.5-flash-preview",
        "gemini-2.5-flash-preview-native-audio-dialog",
        "gemini-2.0-flash-live-001"
    );
    private String selectedModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        selectedModel = models.get(0); // Default to first model
        
        checkPermissions();
        setupUI();
        prepareNewClient();
    }

    private void setupUI() {
        translationAdapter = new TranslationAdapter();
        binding.transcriptLog.setLayoutManager(new LinearLayoutManager(this));
        binding.transcriptLog.setAdapter(translationAdapter);

        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        binding.modelSpinner.setAdapter(modelAdapter);
        binding.modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!selectedModel.equals(models.get(position))) {
                    selectedModel = models.get(position);
                    if (isSessionActive) {
                        Toast.makeText(MainActivity.this, "Changing model requires reconnect", Toast.LENGTH_SHORT).show();
                        teardownSession(true); // Reconnect
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        binding.micBtn.setOnClickListener(v -> handleMasterButton());
        binding.settingsBtn.setOnClickListener(v -> handleSettingsDisconnectButton());
        updateUI();
    }
    
    private void prepareNewClient() {
        SharedPreferences sharedPrefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE);
        String selectedApiVersion = sharedPrefs.getString("api_version", "v1alpha");

        webSocketClient = new WebSocketClient(
            getApplicationContext(),
            selectedModel,
            getVadSensitivity(),
            selectedApiVersion,
            () -> mainHandler.post(() -> { // onOpen
                isSessionActive = true;
                updateStatus("Connected, awaiting server...");
                updateUI();
            }),
            text -> mainHandler.post(() -> processServerMessage(text)), // onMessage
            (code, reason) -> mainHandler.post(() -> { // onClosing
                Log.w(TAG, "WebSocket closing: " + code + " - " + reason);
                teardownSession(false);
            }),
            t -> mainHandler.post(() -> { // onFailure
                showError("Connection error: " + t.getMessage());
                teardownSession(false);
            }),
            () -> mainHandler.post(() -> { // onSetupComplete
                isServerReady = true;
                updateStatus("Ready to listen");
                updateUI();
            })
        );
    }
    
    private void handleMasterButton() {
        if (!isSessionActive) {
            connect();
        } else {
            toggleListening();
        }
    }

    private void handleSettingsDisconnectButton() {
        if (isSessionActive) {
            teardownSession(false);
        } else {
            showSettingsDialog();
        }
    }

    private void showSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE));
        dialog.show();
    }

    private int getVadSensitivity() {
        return getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800);
    }
    
    private void connect() {
        if (isSessionActive) return;
        updateStatus("Connecting...");
        updateUI();
        webSocketClient.connect();
    }

    private void processServerMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("serverContent")) {
                JSONObject content = json.getJSONObject("serverContent");
                if (content.has("parts")) {
                    JSONArray parts = content.getJSONArray("parts");
                    StringBuilder textContent = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i);
                        if (part.has("text") && !part.getString("text").isEmpty()) {
                            if (textContent.length() > 0) textContent.append("\n");
                            textContent.append(part.getString("text"));
                        }
                    }
                    if (textContent.length() > 0) {
                        translationAdapter.addOrUpdateTranslation(textContent.toString(), false);
                    }
                }
            } else if (json.has("inputTranscription")) {
                JSONObject transcription = json.getJSONObject("inputTranscription");
                if (transcription.has("text")) {
                    translationAdapter.addOrUpdateTranslation(transcription.getString("text"), true);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing server message", e);
        }
    }

    private void toggleListening() {
        if (!isServerReady) return;
        isListening = !isListening;
        if (isListening) {
            startAudio();
        } else {
            stopAudio();
        }
        updateUI();
    }

    private void startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            isListening = false;
            return;
        }
        audioHandler = new AudioHandler(this, audioData -> {
            if (webSocketClient != null && webSocketClient.isReady()) {
                webSocketClient.sendAudio(audioData);
            }
        });
        audioHandler.startRecording();
        updateStatus("Listening...");
    }

    private void stopAudio() {
        if (audioHandler != null) {
            audioHandler.stopRecording();
        }
        updateStatus("Ready to listen");
    }
    
    private void teardownSession(boolean reconnect) {
        if (!isSessionActive) return;

        isListening = false;
        isSessionActive = false;
        isServerReady = false;

        if (audioHandler != null) {
            audioHandler.stopRecording();
        }
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }

        mainHandler.post(() -> {
            if (!reconnect) {
                updateStatus("Disconnected");
            }
            updateUI();
            prepareNewClient();
            if (reconnect) {
                connect();
            }
        });
    }

    private void updateUI() {
        binding.modelSpinner.setEnabled(!isSessionActive);
        binding.settingsBtn.setText(isSessionActive ? "Disconnect" : "Settings");

        if (!isSessionActive) {
            binding.micBtn.setText("Connect");
            binding.micBtn.setEnabled(true);
        } else {
            binding.micBtn.setEnabled(isServerReady);
            if (!isServerReady) {
                binding.micBtn.setText("Connecting...");
            } else if (isListening) {
                binding.micBtn.setText("Stop");
            } else {
                binding.micBtn.setText("Start Listening");
            }
        }
        binding.interimDisplay.setVisibility(isListening ? View.VISIBLE : View.GONE);
    }
    
    private void updateStatus(String message) {
        binding.statusText.setText("Status: " + message);
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        updateStatus(message);
    }
    
    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showError("Audio permission required");
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        teardownSession(false);
        mainHandler.removeCallbacksAndMessages(null); // Clean up handler
    }
}
