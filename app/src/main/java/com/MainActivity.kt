package com.gemweblive

import android.Manifest
import android.app.Activity
import android.content.Context
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
import com.gemweblive.util.ConfigBuilder
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.lang.StringBuilder

// Data class definitions for parsing server responses
data class ServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContent?, @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?, @SerializedName("setupComplete") val setupComplete: SetupComplete?,
    @SerializedName("sessionResumptionUpdate") val sessionResumptionUpdate: SessionResumptionUpdate?, @SerializedName("goAway") val goAway: GoAway?
)
data class ServerContent(@SerializedName("parts") val parts: List<Part>?, @SerializedName("modelTurn") val modelTurn: ModelTurn?, @SerializedName("inputTranscription") val inputTranscription: Transcription?, @SerializedName("outputTranscription") val outputTranscription: Transcription?)
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
    private lateinit var configBuilder: ConfigBuilder
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()

    // State Management
    private var sessionHandle: String? = null
    private val outputTranscriptBuffer = StringBuilder()
    @Volatile private var isListening = false
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false

    // Model and API Configuration
    private lateinit var currentModelInfo: ModelInfo
    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()

    companion object {
        private const val TAG = "MainActivity"
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                modelName = "gemini-2.5-flash-preview-native-audio-dialog",
                displayName = "Live (Flash Audio)",
                inputType = InputType.AUDIO,
                outputType = OutputType.AUDIO_AND_TEXT,
                isLiveModel = true,
                supportsSystemInstruction = true,
                supportsInputAudioTranscription = true,
                supportsOutputAudioTranscription = true,
                supportsContextWindowCompression = true,
                supportsSafetySettings = true,
                supportsThinkingConfig = true
            ),
            ModelInfo(
                modelName = "gemini-2.0-flash-live-001",
                displayName = "Flash 2.0",
                inputType = InputType.AUDIO,
                outputType = OutputType.AUDIO_AND_TEXT,
                isLiveModel = true, // This model would use a REST client
                supportsSystemInstruction = true,
                supportsSafetySettings = true,
                supportsThinkingConfig = true
            ),
            ModelInfo(
                modelName = "gemini-2.5-flash-live-preview",
                displayName = "Transcribe (Text Only)",
                inputType = InputType.AUDIO,
                outputType = OutputType.AUDIO_AND_TEXT,
                isLiveModel = true, // This model would use a REST client
                supportsSystemInstruction = true,
                supportsSafetySettings = true,
                supportsThinkingConfig = true
            ),
            
            ModelInfo(
                modelName = "gemini-2.0-latest",
                displayName = "Transcribe (Text Only)",
                inputType = InputType.TEXT,
                outputType = OutputType.TEXT,
                isLiveModel = true, // This model would use a REST client
                supportsSystemInstruction = true,
                supportsSafetySettings = true,
                supportsThinkingConfig = true
            )
        )
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var speechRecognitionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configBuilder = ConfigBuilder(gson)
        setupLaunchers()
        loadApiVersionsFromResources()
        loadApiKeysFromResources()
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
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                    binding.textInput.setText(it)
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

    private fun loadApiVersionsFromResources() {
        val rawApiVersions = resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            parsedList.add(if (parts.size == 2) ApiVersion(parts[0].trim(), parts[1].trim()) else ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersions = parsedList
    }

    private fun loadApiKeysFromResources() {
        val rawApiKeys = resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
        }
        apiKeys = parsedList
    }

    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.layoutManager = LinearLayoutManager(this)
        binding.transcriptLog.adapter = translationAdapter
        binding.debugConnectBtn.setOnClickListener { connect() }
        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        binding.sendTextBtn.setOnClickListener { handleSendTextButton() }
        updateUI()
    }

    private fun updateUiMode() {
        val isTextMode = currentModelInfo.inputType == InputType.TEXT
        binding.textInput.visibility = if (isTextMode) View.VISIBLE else View.GONE
        binding.sendTextBtn.visibility = if (isTextMode) View.VISIBLE else View.GONE
        updateUI() // Refresh button text
    }

    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData -> webSocketClient?.sendAudio(audioData) }
        }
        prepareNewClient()
    }

    private fun prepareNewClient() {
        webSocketClient?.disconnect()
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        val selectedApiVersion = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        val selectedApiKey = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()

        if (currentModelInfo.isLiveModel) {
            webSocketClient = WebSocketClient(
                context = this,
                modelInfo = currentModelInfo,
                configBuilder = configBuilder,
                vadSilenceMs = getVadSensitivity(),
                apiVersion = selectedApiVersion?.value ?: "v1beta",
                apiKey = selectedApiKey?.value ?: "",
                sessionHandle = sessionHandle,
                onOpen = { mainScope.launch { isSessionActive = true; updateStatus("Connected..."); updateUI() } },
                onMessage = { text -> mainScope.launch { processServerMessage(text) } },
                onClosing = { _, _ -> mainScope.launch { teardownSession(reconnect = true) } },
                onFailure = { t -> mainScope.launch { showError("Connection error: ${t.message}"); teardownSession() } },
                onSetupComplete = { mainScope.launch { isServerReady = true; updateStatus("Ready"); updateUI() } }
            )
        } else {
            // Here you would initialize your RestApiClient for text-only models
            // For now, we can just log a message.
            Log.i(TAG, "Text-only model selected. Live connection not initiated.")
        }
    }

    private fun handleMasterButton() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
        if (currentModelInfo.inputType == InputType.AUDIO) {
            if (!isSessionActive) connect() else toggleListening()
        } else {
            startOnDeviceSpeechToText()
        }
    }

    private fun handleSendTextButton() {
        val textToSend = binding.textInput.text.toString()
        if (textToSend.isNotBlank()) {
            if (!currentModelInfo.isLiveModel) {
                // TODO: Implement REST API call here
                showError("REST API not implemented yet.")
                translationAdapter.addOrUpdateTranslation(textToSend, true)
                binding.textInput.text.clear()
                return
            }

            if (!isSessionActive || !isServerReady) {
                showError("Not connected. Please connect first.")
                return
            }
            webSocketClient?.sendText(textToSend) // Assuming you add this function to WebSocketClient
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
                Toast.makeText(this, "Settings changed. Please disconnect and reconnect to apply.", Toast.LENGTH_LONG).show()
            } else {
                // If not connected, we can prepare the new client immediately
                prepareNewClient()
            }
        }
        dialog.show()
    }

    private fun getVadSensitivity(): Int = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)

    private fun connect() {
        if (isSessionActive || !currentModelInfo.isLiveModel) return
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
                part.inlineData?.data?.let { audioPlayer.playAudio(it) }
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
                delay(1000)
                connect()
            }
        }
    }

    private fun updateUI() {
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"
        if (!isSessionActive) {
            binding.micBtn.text = if (currentModelInfo.inputType == InputType.AUDIO) "Connect" else "Transcribe"
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
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        val currentApiVersion = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        val currentApiKey = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()
        binding.configDisplay.text = "Model: ${currentModelInfo.displayName} | Version: ${currentApiVersion?.displayName ?: "N/A"} | Key: ${currentApiKey?.displayName ?: "N/A"}"
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        teardownSession()
        mainScope.cancel()
    }
}
