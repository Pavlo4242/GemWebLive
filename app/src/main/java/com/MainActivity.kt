// app/src/main/java/com/gemweblive/MainActivity.kt
package com.gemweblive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gemweblive.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.lang.StringBuilder

// --- Data classes for parsing server responses ---
data class ServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContent?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?,
    @SerializedName("setupComplete") val setupComplete: SetupComplete?,
    @SerializedName("sessionResumptionUpdate") val sessionResumptionUpdate: SessionResumptionUpdate?,
    @SerializedName("goAway") val goAway: GoAway?
)

data class ServerContent(
    @SerializedName("parts") val parts: List<Part>?,
    @SerializedName("modelTurn") val modelTurn: ModelTurn?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?
)

data class ModelTurn(@SerializedName("parts") val parts: List<Part>?)
data class Part(@SerializedName("text") val text: String?, @SerializedName("inlineData") val inlineData: InlineData?)
data class InlineData(@SerializedName("mime_type") val mimeType: String?, @SerializedName("data") val data: String?)
data class Transcription(@SerializedName("text") val text: String?)
data class SetupComplete(val dummy: String? = null)
data class SessionResumptionUpdate(@SerializedName("newHandle") val newHandle: String?, @SerializedName("resumable") val resumable: Boolean?)
data class GoAway(@SerializedName("timeLeft") val timeLeft: String?)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioHandler: AudioHandler
    private var webSocketClient: WebSocketClient? = null
    private lateinit var translationAdapter: TranslationAdapter
    private lateinit var audioPlayer: AudioPlayer
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()

    // --- State Management ---
    private var sessionHandle: String? = null
    private val outputTranscriptBuffer = StringBuilder()
    @Volatile private var isListening = false
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false

    // --- Model and API Configuration ---
    private lateinit var currentModelInfo: ModelInfo
    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()
    private var selectedApiVersionObject: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null

    companion object {
        private const val TAG = "MainActivity"

        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                modelName = "gemini-live-2.5-flash-preview",
                displayName = "Live (Audio In / Audio Out)",
                supportsAudioInput = true,
                supportsAudioOutput = true
            ),
            ModelInfo(
                modelName = "gemini-2.0-text-latest", // Example
                displayName = "Transcribe (Text In / Text Out)",
                supportsAudioInput = false,
                supportsAudioOutput = false
            ),
            ModelInfo(
                modelName = "gemini-2.0-audio-text-latest", // Example
                displayName = "Assistant (Audio In / Text Out)",
                supportsAudioInput = true,
                supportsAudioOutput = false
            )
        )
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var speechRecognitionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Initialization ---
        setupLaunchers()
        loadApiVersionsFromResources(this)
        loadApiKeysFromResources(this)
        loadPreferences()

        audioPlayer = AudioPlayer()

        checkPermissions()
        setupUI()
        updateDisplayInfo()
        updateUiMode()
    }

    private fun setupLaunchers() {
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) initializeComponentsDependentOnAudio() else showError("Microphone permission is required.")
        }

        speechRecognitionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!data.isNullOrEmpty()) {
                    binding.textInput.setText(data[0])
                    updateStatus("Review transcript, then press Send.")
                }
            } else {
                updateStatus("Transcription cancelled or failed.")
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        val savedModelName = prefs.getString("selected_model", AVAILABLE_MODELS[0].modelName)
        currentModelInfo = AVAILABLE_MODELS.find { it.modelName == savedModelName } ?: AVAILABLE_MODELS[0]
        sessionHandle = prefs.getString("session_handle", null)
    }

    private fun loadApiVersionsFromResources(context: Context) {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            parsedList.add(if (parts.size == 2) ApiVersion(parts[0].trim(), parts[1].trim()) else ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersions = parsedList
        selectedApiVersionObject = parsedList.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_version", null) } ?: parsedList.firstOrNull()
    }

    private fun loadApiKeysFromResources(context: Context) {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
        }
        apiKeys = parsedList
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_key", null) } ?: parsedList.firstOrNull()
    }

    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = translationAdapter
        }
        binding.debugConnectBtn.setOnClickListener { handleDebugConnectButton() }
        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        binding.sendTextBtn.setOnClickListener { handleSendTextButton() } // Listener for the new button
        updateUI()
    }
    
    private fun updateUiMode() {
        val isLiveAudioMode = currentModelInfo.supportsAudioInput
        binding.textInput.visibility = if (isLiveAudioMode) View.GONE else View.VISIBLE
        binding.sendTextBtn.visibility = if (isLiveAudioMode) View.GONE else View.VISIBLE
    }

    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                webSocketClient?.sendAudio(audioData)
            }
        }
        prepareNewClient()
    }

    private fun prepareNewClient() {
        webSocketClient?.disconnect()

        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()

        webSocketClient = WebSocketClient(
            context = applicationContext,
            modelName = currentModelInfo.modelName, // Use the name from ModelInfo
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1alpha",
            apiKey = selectedApiKeyInfo?.value ?: "",
            sessionHandle = sessionHandle,
            onOpen = { mainScope.launch {
                isSessionActive = true
                updateStatus("Connected, awaiting server...")
                updateUI()
            }},
            onMessage = { text -> mainScope.launch { processServerMessage(text) } },
            onClosing = { _, _ -> mainScope.launch { teardownSession(reconnect = true) } }, // Attempt to reconnect on close
            onFailure = { t -> mainScope.launch {
                showError("Connection error: ${t.message}")
                teardownSession()
            }},
            onSetupComplete = { mainScope.launch {
                isServerReady = true
                updateStatus(if(currentModelInfo.supportsAudioInput) "Ready to listen" else "Ready to transcribe")
                updateUI()
            }}
        )
    }

    // --- Button Handlers ---
    private fun handleDebugConnectButton() { connect() }

    private fun handleMasterButton() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
        if (currentModelInfo.supportsAudioInput) {
            if (!isSessionActive) connect() else toggleListening()
        } else {
            startOnDeviceSpeechToText()
        }
    }

    private fun handleSendTextButton() {
        val textToSend = binding.textInput.text.toString()
        if (textToSend.isNotBlank()) {
            if (!isSessionActive || !isServerReady) {
                showError("Not connected. Please connect first.")
                return
            }
            webSocketClient?.sendText(textToSend)
            translationAdapter.addOrUpdateTranslation(textToSend, true)
            binding.textInput.text.clear()
        }
    }

    private fun handleSettingsDisconnectButton() {
        if (isSessionActive) teardownSession() else showSettingsDialog()
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE), AVAILABLE_MODELS)
        dialog.setOnDismissListener {
            loadPreferences()
            updateDisplayInfo()
            updateUiMode()
            if (isSessionActive) {
                Toast.makeText(this, "Settings saved. Disconnect and reconnect to apply.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    // --- Core Logic ---
    private fun getVadSensitivity(): Int = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)

    private fun connect() {
        if (isSessionActive) return
        updateStatus("Connecting...")
        updateUI()
        webSocketClient?.connect()
    }

    private fun startOnDeviceSpeechToText() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("On-device speech recognition is not available.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        try {
            updateStatus("Transcribing...")
            speechRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            showError("Could not launch speech recognizer.")
        }
    }

    private fun processServerMessage(text: String) {
        try {
            val response = gson.fromJson(text, ServerResponse::class.java)

            response.sessionResumptionUpdate?.let {
                if (it.resumable == true && it.newHandle != null) {
                    sessionHandle = it.newHandle
                    getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).edit().putString("session_handle", sessionHandle).apply()
                    Log.i(TAG, "Session handle updated.")
                }
            }

            response.goAway?.timeLeft?.let { showError("Connection closing in $it. Will reconnect.") }

            val outputText = response.outputTranscription?.text ?: response.serverContent?.outputTranscription?.text
            if (outputText != null) outputTranscriptBuffer.append(outputText)

            val inputText = response.inputTranscription?.text ?: response.serverContent?.inputTranscription?.text
            if (inputText != null && inputText.isNotBlank()) {
                if (outputTranscriptBuffer.isNotEmpty()) {
                    translationAdapter.addOrUpdateTranslation(outputTranscriptBuffer.toString().trim(), false)
                    outputTranscriptBuffer.clear()
                }
                translationAdapter.addOrUpdateTranslation(inputText.trim(), true)
            }

            val modelTurnParts = response.serverContent?.modelTurn?.parts ?: response.serverContent?.parts
            modelTurnParts?.forEach { part ->
                part.inlineData?.data?.let { if (currentModelInfo.supportsAudioOutput) audioPlayer.playAudio(it) }
                part.text?.let { if (!currentModelInfo.supportsAudioOutput) translationAdapter.addOrUpdateTranslation(it, false) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing server message: $text", e)
        }
    }

    private fun toggleListening() {
        if (!isServerReady) return
        isListening = !isListening
        if (isListening) startAudio() else stopAudio()
        updateUI()
    }

    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            isListening = false
            return
        }
        audioHandler.startRecording()
        updateStatus("Listening...")
    }

    private fun stopAudio() {
        if (::audioHandler.isInitialized) audioHandler.stopRecording()
        if (outputTranscriptBuffer.isNotEmpty()) {
            translationAdapter.addOrUpdateTranslation(outputTranscriptBuffer.toString().trim(), false)
            outputTranscriptBuffer.clear()
        }
        updateStatus("Ready to listen")
    }

    private fun teardownSession(reconnect: Boolean = false) {
        if (!isSessionActive) return
        isListening = false
        isSessionActive = false
        isServerReady = false
        if (::audioHandler.isInitialized) audioHandler.stopRecording()
        webSocketClient?.disconnect()
        mainScope.launch {
            if (!reconnect) updateStatus("Disconnected")
            updateUI()
            prepareNewClient()
            if (reconnect) {
                delay(1000) // Brief delay before reconnecting
                connect()
            }
        }
    }

    private fun updateUI() {
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"
        if (!isSessionActive) {
            binding.micBtn.text = "Connect"
            binding.micBtn.isEnabled = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            binding.debugConnectBtn.isEnabled = true
        } else {
            binding.micBtn.isEnabled = isServerReady
            binding.micBtn.text = when {
                !isServerReady -> "Connecting..."
                isListening -> "Stop"
                else -> "Start Listening"
            }
            binding.debugConnectBtn.isEnabled = false
        }
        binding.interimDisplay.visibility = if (isListening) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus("Alert: $message")
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initializeComponentsDependentOnAudio()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateDisplayInfo() {
        val currentApiVersionDisplayName = selectedApiVersionObject?.displayName ?: "N/A"
        val currentApiKeyDisplayName = selectedApiKeyInfo?.displayName ?: "N/A"
        binding.configDisplay.text = "Model: ${currentModelInfo.displayName} | Version: $currentApiVersionDisplayName | Key: $currentApiKeyDisplayName"
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        teardownSession()
        mainScope.cancel()
    }
}
