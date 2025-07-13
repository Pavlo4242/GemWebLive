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
                    Log.d(TAG, "WebSocket opened, sending config...")
                }
            },
            onMessage = { text ->
                mainScope.launch {
                    Log.d(TAG, "Raw message: $text")
                    processServerMessage(text)
                }
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
        
        mainScope.launch(Dispatchers.IO) {
            webSocketClient.connect()
        }
    }

    private fun processServerMessage(text: String) {
        Log.d(TAG, "RAW MESSAGE: ${text.take(500)}")
        
        try {
            val response = JSONObject(text)
            Log.d(TAG, "PARSED JSON KEYS: ${response.keys().asSequence().toList().joinToString()}")

            when {
                response.has("setupComplete") -> {
                    isServerSetupComplete = response.getBoolean("setupComplete")
                    Log.i(TAG, "SERVER SETUP COMPLETE: $isServerSetupComplete")
                    updateStatus("Connected. Click 'Start Listening'.")
                    updateUI()
                    
                    Log.d(TAG, "SETUP COMPLETE DETAILS: ${response.toString(2)}")
                }

                response.has("serverContent") -> {
                    val serverContent = response.getJSONObject("serverContent")
                    Log.d(TAG, "SERVER CONTENT KEYS: ${serverContent.keys().asSequence().toList().joinToString()}")
                    
                    serverContent.optJSONObject("inputTranscription")?.let { 
                        Log.d(TAG, "INPUT TRANS: ${it.toString(2)}")
                        it.optString("text")?.let { text ->
                            translationAdapter.addOrUpdateTranslation(text, true)
                        }
                    }
                    
                    serverContent.optJSONObject("outputTranscription")?.let {
                        Log.d(TAG, "OUTPUT TRANS: ${it.toString(2)}")
                        it.optString("text")?.let { text ->
                            translationAdapter.addOrUpdateTranslation(text, false)
                        }
                    }
                }
                
                response.has("inputTranscription") -> {
                    val input = response.getJSONObject("inputTranscription")
                    Log.d(TAG, "STANDALONE INPUT: ${input.toString(2)}")
                    input.optString("text")?.let {
                        translationAdapter.addOrUpdateTranslation(it, true)
                    }
                }

                response.has("outputTranscription") -> {
                    val output = response.getJSONObject("outputTranscription")
                    Log.d(TAG, "STANDALONE OUTPUT: ${output.toString(2)}")
                    output.optString("text")?.let {
                        translationAdapter.addOrUpdateTranslation(it, false)
                    }
                }

                response.has("error") -> {
                    val error = response.getJSONObject("error")
                    Log.e(TAG, "SERVER ERROR: ${error.toString(2)}")
                    showError("API Error: ${error.getString("message")}")
                }

                else -> {
                    Log.w(TAG, "UNHANDLED MESSAGE TYPE. FULL MESSAGE:\n${response.toString(2)}")
                }
            }
            
            if (response.has("inputTranscription") || response.has("outputTranscription") || response.has("serverContent")) {
                binding.transcriptLog.scrollToPosition(0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "MESSAGE PARSING ERROR. Original text: $text", e)
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
        isServerSetupComplete = false
        updateStatus("Disconnected")
        updateUI()
    }

    private fun updateUI() {
        binding.micBtn.isEnabled = isServerSetupComplete

        if (!isConnected) {
            binding.micBtn.text = "Connect"
            binding.micBtn.isEnabled = true
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
