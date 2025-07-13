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
                mainScope.launch { processServerMessage(text) }
            },
            onClosing = { code, reason ->
                mainScope.launch {
                    Log.d(TAG, "WebSocket Closing: $code $reason")
                    teardownSession()
                }
            },
            onFailure = { t, response ->
                mainScope.launch {
                    Log.e(TAG, "WebSocket Failure: ${t.message}", t)
                    showError("Connection failed: ${t.message}")
                    teardownSession()
                }
            }
        )
        webSocketClient.connect()
    }

    private fun processServerMessage(text: String) {
        Log.d(TAG, "Received: $text")
        try {
            val response = JSONObject(text)
            when {
                response.has("setupComplete") -> {
                    updateStatus("Connected. Click 'Start Listening'.")
                    updateUI()
                }
                response.has("serverContent") -> {
                    val serverContent = response.getJSONObject("serverContent")
                    val inputTranscription = serverContent.optJSONObject("inputTranscription")?.optString("text")
                    val outputTranscription = serverContent.optJSONObject("outputTranscription")?.optString("text")

                    inputTranscription?.let {
                        translationAdapter.addOrUpdateTranslation(it, true)
                    }
                    outputTranscription?.let {
                        translationAdapter.addOrUpdateTranslation(it, false)
                    }
                     binding.transcriptLog.scrollToPosition(0)
                }
                 response.has("error") -> {
                    val error = response.getJSONObject("error").getString("message")
                    showError("API Error: $error")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message", e)
        }
    }

    private fun toggleListening() {
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
        updateStatus("Disconnected")
        updateUI()
    }

     private fun updateUI() {
        binding.micBtn.isEnabled = true
        if (!isConnected) {
            binding.micBtn.text = "Connect"
            binding.settingsBtn.text = "Settings"
            binding.interimDisplay.visibility = View.GONE
            binding.modelSpinner.isEnabled = true
        } else {
            binding.modelSpinner.isEnabled = false
            binding.settingsBtn.text = "Disconnect"
            if (isListening) {
                binding.micBtn.text = "Stop"
                binding.interimDisplay.visibility = View.VISIBLE
            } else {
                binding.micBtn.text = "Start Listening"
                binding.interimDisplay.visibility = View.GONE
            }
        }
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
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
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Permission to record audio is required for this app to function.", Toast.LENGTH_LONG).show()
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
