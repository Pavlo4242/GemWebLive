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
    private var isListening = false
    private var isConnected = false
    private var isServerSetupComplete = false

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

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                reverseLayout = true
            }
            adapter = translationAdapter
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModel = models[position]
                if (isConnected) {
                    Toast.makeText(this@MainActivity, "Reconnect to apply new model.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
    }

    private fun handleMasterButton() {
        if (!isConnected) {
            updateStatus("Connecting...")
            binding.micBtn.isEnabled = false
            connect()
        } else {
            toggleListening()
        }
    }

    private fun handleSettingsDisconnectButton() {
        if (isConnected) {
            teardownSession()
        } else {
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        val settingsDialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE))
        settingsDialog.show()
    }

    private fun getVadSensitivity(): Int {
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        return prefs.getInt("vad_sensitivity_ms", 800)
    }

    private fun connect() {
        isServerSetupComplete = false
        
        webSocketClient = WebSocketClient(
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            onOpen = {
                mainScope.launch {
                    isConnected = true
                    updateStatus("Awaiting server setup...")
                    updateUI()
                }
            },
            onMessage = { text ->
                mainScope.launch {
                    processServerMessage(text)
                }
            },
            onClosing = { _, _ ->
                mainScope.launch {
                    teardownSession()
                }
            },
            onFailure = { t, _ ->
                mainScope.launch {
                    showError("Connection failed: ${t.message}")
                    teardownSession()
                }
            },
            // Correctly passing the named parameter
            onSetupComplete = {
                mainScope.launch {
                    isServerSetupComplete = true
                    updateStatus("Connected. Click 'Start Listening'.")
                    updateUI()
                }
            }
        )
        
        mainScope.launch(Dispatchers.IO) {
            webSocketClient.connect()
        }
    }

    private fun processServerMessage(text: String) {
        try {
            val response = JSONObject(text)
            
            val serverContent = response.optJSONObject("serverContent")
            if (serverContent != null) {
                serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, true)
                }
                serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, false)
                }
            } else {
                response.optJSONObject("inputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, true)
                }
                response.optJSONObject("outputTranscription")?.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, false)
                }
            }
            
            response.optJSONObject("error")?.getString("message")?.let {
                showError("API Error: $it")
            }

            binding.transcriptLog.scrollToPosition(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    private fun toggleListening() {
        if (!isServerSetupComplete) {
            Toast.makeText(this, "Please wait for server setup to complete.", Toast.LENGTH_SHORT).show()
            return
        }
        isListening = !isListening
        if (isListening) {
            startAudio()
        } else {
            stopAudio()
        }
        updateUI()
    }

    private fun startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // Now correctly passes the ByteArray to the WebSocketClient
            audioHandler = AudioHandler(this) { audioData ->
                if (isListening && ::webSocketClient.isInitialized && webSocketClient.isConnected()) {
                    webSocketClient.sendAudio(audioData)
                }
            }
            audioHandler.startRecording()
            updateStatus("Listening...")
        } else {
            isListening = false
            Toast.makeText(this, "Audio permission not granted.", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    private fun stopAudio() {
        if(::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        updateStatus("Paused.")
    }

    private fun teardownSession() {
        stopAudio()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
        isConnected = false
        isListening = false
        isServerSetupComplete = false
        updateStatus("Disconnected")
        updateUI()
    }

    private fun updateUI() {
        binding.micBtn.isEnabled = true 

        if (!isConnected) {
            binding.micBtn.text = "Connect"
            binding.settingsBtn.text = "Settings"
            binding.modelSpinner.isEnabled = true
        } else {
            binding.settingsBtn.text = "Disconnect"
            binding.modelSpinner.isEnabled = false
            if (isServerSetupComplete) {
                binding.micBtn.text = if (isListening) "Stop" else "Start Listening"
            } else {
                binding.micBtn.text = "Connecting..."
                binding.micBtn.isEnabled = false
            }
        }
        binding.interimDisplay.visibility = if (isListening) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus("Error: $message")
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Permission to record audio is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        mainScope.cancel()
    }
}
