package com.gemweblive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gemweblive.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioHandler: AudioHandler
    private lateinit var webSocketClient: WebSocketClient
    private lateinit var translationAdapter: TranslationAdapter

    private val mainScope = CoroutineScope(Dispatchers.Main)

    // Simplified state flags
    private var isListening = false
    private var isSessionActive = false
    private var isServerReady = false

    private val models = listOf(
        "gemini-2.5-flash-preview-native-audio-dialog",
        "gemini-2.0-flash-live-001",
        "gemini-live-2.5-flash-preview"
    )
    private var selectedModel = models[0]

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        setupUI()
        setupWebSocketClient() // Initial setup
    }
    
    // UI setup is cleaner
    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = translationAdapter
        }

        binding.modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (selectedModel != models[position]) {
                    selectedModel = models[position]
                    if (isSessionActive) {
                        Toast.makeText(this@MainActivity, "Reconnecting...", Toast.LENGTH_SHORT).show()
                        teardownSession(reconnect = true)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        updateUI() // Initial UI state
    }

    // WebSocket client is now re-initialized cleanly when needed
    private fun setupWebSocketClient() {
        webSocketClient = WebSocketClient(
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            onOpen = {
                mainScope.launch {
                    isSessionActive = true
                    updateStatus("Connected, waiting for server...")
                    updateUI()
                }
            },
            onMessage = { text ->
                mainScope.launch { processServerMessage(text) }
            },
            onClosing = { _, _ ->
                mainScope.launch { teardownSession() }
            },
            onFailure = { t, _ ->
                mainScope.launch {
                    showError("Connection Error: ${t.message}")
                    teardownSession()
                }
            },
            onSetupComplete = {
                mainScope.launch {
                    isServerReady = true
                    updateStatus("Ready. Click to start listening.")
                    updateUI()
                }
            }
        )
    }

    private fun handleMasterButton() {
        if (!isSessionActive) {
            connect()
        } else {
            toggleListening()
        }
    }
    
    private fun handleSettingsDisconnectButton() {
        if (isSessionActive) {
            teardownSession()
        } else {
            // Show settings dialog
        }
    }
    
    private fun getVadSensitivity(): Int {
        return getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
    }

    private fun connect() {
        if (isSessionActive) return
        updateStatus("Connecting...")
        updateUI()
        webSocketClient.connect()
    }

    private fun processServerMessage(text: String) {
        try {
            val response = JSONObject(text)
            // Process transcriptions and other messages
            response.optJSONObject("serverContent")?.let { content ->
                content.optJSONObject("inputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, true)
                }
                content.optJSONObject("outputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, false)
                }
            }
            // ... (handle other message types if necessary)
            binding.transcriptLog.scrollToPosition(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    private fun toggleListening() {
        if (!isServerReady) return // Guard against premature listening
        isListening = !isListening
        if (isListening) startAudio() else stopAudio()
        updateUI()
    }

    private fun startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioHandler = AudioHandler(this) { audioData ->
                if (webSocketClient.isReady()) {
                    webSocketClient.sendAudio(audioData)
                }
            }
            audioHandler.startRecording()
            updateStatus("Listening...")
        } else {
            isListening = false
            checkPermissions()
        }
    }

    private fun stopAudio() {
        if (::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        updateStatus("Paused. Click to resume.")
    }

    // Simplified teardown and reconnect logic
    private fun teardownSession(reconnect: Boolean = false) {
        if (!isSessionActive) return
        stopAudio()
        webSocketClient.disconnect()
        isListening = false
        isSessionActive = false
        isServerReady = false
        
        mainScope.launch {
            if (!reconnect) {
                 updateStatus("Disconnected")
                 updateUI()
            }
            // Always prepare a fresh client for the next connection attempt
            setupWebSocketClient()
            if (reconnect) {
                connect()
            }
        }
    }

    private fun updateUI() {
        binding.modelSpinner.isEnabled = !isSessionActive
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"

        if (!isSessionActive) {
            binding.micBtn.text = "Connect"
            binding.micBtn.isEnabled = true
        } else {
            if (!isServerReady) {
                binding.micBtn.text = "Connecting..."
                binding.micBtn.isEnabled = false
            } else {
                binding.micBtn.text = if (isListening) "Stop" else "Start Listening"
                binding.micBtn.isEnabled = true
            }
        }
        binding.interimDisplay.visibility = if (isListening) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus(message)
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION && !(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
            showError("Audio permission is required.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        mainScope.cancel()
    }
}
