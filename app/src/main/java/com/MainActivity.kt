package com.gemweblive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
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

    // --- Configuration (Simple Version) ---
    private val models = listOf("gemini-1.5-flash-preview", "gemini-1.5-pro-preview")
    private var selectedModel: String = models[0]
    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()
    private var selectedApiVersionObject: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadApiVersionsFromResources()
        loadApiKeysFromResources()
        loadPreferences()

        audioPlayer = AudioPlayer()
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) initializeComponentsDependentOnAudio() else showError("Microphone permission is required.")
        }

        checkPermissions()
        setupUI()
        updateDisplayInfo()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
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
        selectedApiVersionObject = parsedList.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_version", null) } ?: parsedList.firstOrNull()
    }

    private fun loadApiKeysFromResources() {
        val rawApiKeys = resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
        }
        apiKeys = parsedList
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_key", null) } ?: apiKeys.firstOrNull()
    }

    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = translationAdapter
        }
        binding.debugConnectBtn.setOnClickListener { connect() }
        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        updateUI()
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
        loadPreferences() // Reload preferences to get the latest settings
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_version", null) } ?: apiVersions.firstOrNull()
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_key", null) } ?: apiKeys.firstOrNull()

        webSocketClient = WebSocketClient(
            context = applicationContext,
            modelName = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1beta",
            apiKey = selectedApiKeyInfo?.value ?: "",
            sessionHandle = sessionHandle,
            onOpen = { mainScope.launch { isSessionActive = true; updateStatus("Connected..."); updateUI() } },
            onMessage = { text -> mainScope.launch { processServerMessage(text) } },
            onClosing = { _, _ -> mainScope.launch { teardownSession(reconnect = true) } },
            onFailure = { t -> mainScope.launch { showError("Connection error: ${t.message}"); teardownSession() } },
            onSetupComplete = { mainScope.launch { isServerReady = true; updateStatus("Ready to listen"); updateUI() } }
        )
    }

    private fun handleMasterButton() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
        if (!isSessionActive) connect() else toggleListening()
    }

    private fun handleSettingsDisconnectButton() {
        if (isSessionActive) teardownSession() else showSettingsDialog()
    }

    private fun showSettingsDialog() {
        // This now correctly passes the simple list of model names
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE), models)
        dialog.setOnDismissListener {
            loadPreferences()
            updateDisplayInfo()
            if (isSessionActive) {
                Toast.makeText(this, "Settings saved. Please Disconnect and reconnect to apply.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun getVadSensitivity(): Int = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)

    private fun connect() {
        if (isSessionActive) return
        updateStatus("Connecting...")
        updateUI()
        webSocketClient?.connect()
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
            Log.e(TAG, "Error processing message: $text", e)
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
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        val currentApiVersion = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        val currentApiKey = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()

        binding.configDisplay.text = "Model: $selectedModel | Version: ${currentApiVersion?.displayName ?: "N/A"} | Key: ${currentApiKey?.displayName ?: "N/A"}"
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        teardownSession()
        mainScope.cancel()
    }
}
