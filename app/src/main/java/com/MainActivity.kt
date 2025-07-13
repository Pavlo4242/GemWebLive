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

    // State flags
    @Volatile private var isListening = false
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false

    private val models = listOf(
        "gemini-1.5-pro",
        "gemini-1.0-pro",
        "gemini-1.5-flash"
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
        prepareNewClient()
    }

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
                        Toast.makeText(this@MainActivity, "Changing model requires reconnect", Toast.LENGTH_SHORT).show()
                        teardownSession(reconnect = true)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        updateUI()
    }

    private fun prepareNewClient() {
        webSocketClient = WebSocketClient(
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            onOpen = {
                mainScope.launch {
                    isSessionActive = true
                    updateStatus("Connected, awaiting server...")
                    updateUI()
                }
            },
            onMessage = { text ->
                mainScope.launch { processServerMessage(text) }
            },
            onClosing = { code, reason ->
                mainScope.launch {
                    Log.w(TAG, "WebSocket closing: $code - $reason")
                    teardownSession()
                }
            },
            onFailure = { t ->
                mainScope.launch {
                    showError("Connection error: ${t.message}")
                    teardownSession()
                }
            },
            onSetupComplete = {
                mainScope.launch {
                    isServerReady = true
                    updateStatus("Ready to listen")
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
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE))
        dialog.show()
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
        val json = JSONObject(text)
        when {
            json.has("serverContent") -> {
                val content = json.getJSONObject("serverContent")
                content.optJSONArray("parts")?.let { parts ->
                    val textContent = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        part.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                            if (textContent.isNotEmpty()) textContent.append("\n")
                            textContent.append(it)
                        }
                    }
                    if (textContent.isNotEmpty()) {
                        translationAdapter.addOrUpdateTranslation(textContent.toString(), false)
                    }
                }
            }
            json.has("inputTranscription") -> {
                val transcription = json.getJSONObject("inputTranscription")
                transcription.optString("text")?.let {
                    translationAdapter.addOrUpdateTranslation(it, true)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error processing server message", e)
    }
}
    private fun toggleListening() {
        if (!isServerReady) return
        isListening = !isListening
        if (isListening) startAudio() else stopAudio()
        updateUI()
    }

    private fun startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            isListening = false
            return
        }
        audioHandler = AudioHandler(this) { audioData ->
            if (webSocketClient.isReady()) {
                webSocketClient.sendAudio(audioData)
            }
        }
        audioHandler.startRecording()
        updateStatus("Listening...")
    }

    private fun stopAudio() {
        if (::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        updateStatus("Ready to listen")
    }

    private fun teardownSession(reconnect: Boolean = false) {
        if (!isSessionActive) return
        
        isListening = false
        isSessionActive = false
        isServerReady = false
        
        if (::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        webSocketClient.disconnect()

        mainScope.launch {
            if (!reconnect) {
                updateStatus("Disconnected")
            }
            updateUI()
            prepareNewClient()
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
            binding.micBtn.isEnabled = isServerReady
            binding.micBtn.text = when {
                !isServerReady -> "Connecting..."
                isListening -> "Stop"
                else -> "Start Listening"
            }
        }
        binding.interimDisplay.visibility = if (isListening) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        updateStatus(message)
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showError("Audio permission required")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        mainScope.cancel()
    }
}
