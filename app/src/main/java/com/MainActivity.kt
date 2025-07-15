// app/src/main/java/com/gemweblive/MainActivity.kt
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
import com.gemweblive.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.lang.StringBuilder

// --- Updated Data classes for parsing all server responses with Gson ---
data class ServerResponse(
    @SerializedName("serverContent") val serverContent: ServerContent?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?,
    @SerializedName("setupComplete") val setupComplete: SetupComplete?,
    @SerializedName("sessionResumptionUpdate") val sessionResumptionUpdate: SessionResumptionUpdate?,
    @SerializedName("goAway") val goAway: GoAway?
)

// Add a separate class for the nested input/output transcription in serverContent
data class ServerContent(
    @SerializedName("parts") val parts: List<Part>?,
    @SerializedName("modelTurn") val modelTurn: ModelTurn?,
    @SerializedName("inputTranscription") val inputTranscription: Transcription?,
    @SerializedName("outputTranscription") val outputTranscription: Transcription?
)

data class ModelTurn(
    @SerializedName("parts") val parts: List<Part>?
)

data class Part(
    @SerializedName("text") val text: String?,
    @SerializedName("inlineData") val inlineData: InlineData?
)

data class InlineData(
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("data") val data: String? // Base64 audio data
)

data class Transcription(
    @SerializedName("text") val text: String?
)

data class SetupComplete(
    val dummy: String? = null
)

// --- NEW: Data classes for Session Management ---
data class SessionResumptionUpdate(
    @SerializedName("newHandle") val newHandle: String?,
    @SerializedName("resumable") val resumable: Boolean?
)

data class GoAway(
    @SerializedName("timeLeft") val timeLeft: String? // e.g., "10s"
)


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioHandler: AudioHandler
    private var webSocketClient: WebSocketClient? = null
    private lateinit var translationAdapter: TranslationAdapter
    private lateinit var audioPlayer: AudioPlayer
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()

    // --- NEW: State for session handle and transcript buffering ---
    private var sessionHandle: String? = null
    private val outputTranscriptBuffer = StringBuilder()

    @Volatile private var isListening = false
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false

    private val models = listOf(
        "gemini-live-2.5-flash-preview",
        "gemini-2.5-flash-preview-native-audio-dialog",
        "gemini-2.0-flash-live-001"
    )
    private var selectedModel: String = models[0]

    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()
    private var selectedApiVersionObject: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null


    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadApiVersionsFromResources(this)
        loadApiKeysFromResources(this)

        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
        sessionHandle = prefs.getString("session_handle", null) // Load previous handle

        audioPlayer = AudioPlayer()

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                initializeComponentsDependentOnAudio()
            } else {
                showError("Microphone permission is required.")
            }
        }

        checkPermissions()
        setupUI()
        updateDisplayInfo()
    }

    private fun loadApiVersionsFromResources(context: Context) {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            if (parts.size == 2) {
                parsedList.add(ApiVersion(parts[0].trim(), parts[1].trim()))
            } else {
                parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
            }
        }
        apiVersions = parsedList
        val currentApiVersionValue = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_version", null)
        selectedApiVersionObject = parsedList.firstOrNull { it.value == currentApiVersionValue } ?: parsedList.firstOrNull()
    }

    private fun loadApiKeysFromResources(context: Context) {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) {
                parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
            }
        }
        apiKeys = parsedList
        val currentApiKeyValue = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_key", null)
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == currentApiKeyValue } ?: parsedList.firstOrNull()
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

        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()

        webSocketClient = WebSocketClient(
            context = applicationContext,
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1alpha",
            apiKey = selectedApiKeyInfo?.value ?: "",
            sessionHandle = sessionHandle, // Pass the handle to the client
            onOpen = { mainScope.launch {
                isSessionActive = true
                updateStatus("Connected, awaiting server...")
                updateUI()
            }},
            onMessage = { text -> mainScope.launch { processServerMessage(text) } },
            onClosing = { _, _ -> mainScope.launch { teardownSession() } },
            onFailure = { t -> mainScope.launch {
                showError("Connection error: ${t.message}")
                teardownSession()
            }},
            onSetupComplete = { mainScope.launch {
                isServerReady = true
                updateStatus("Ready to listen")
                updateUI()
            }}
        )
    }

    private fun handleDebugConnectButton() {
        connect()
    }

    private fun handleMasterButton() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }
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
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE), models)
        dialog.setOnDismissListener {
            val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
            selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
            selectedApiVersionObject = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
            selectedApiKeyInfo = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()
            updateDisplayInfo()
            if (isSessionActive) {
                Toast.makeText(this, "Settings saved. Please Disconnect and Connect to apply.", Toast.LENGTH_LONG).show()
                teardownSession(reconnect = true)
            }
        }
        dialog.show()
    }


    private fun getVadSensitivity(): Int {
        return getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
    }

    private fun connect() {
        if (isSessionActive) return
        updateStatus("Connecting...")
        updateUI()
        webSocketClient?.connect()
    }

    private fun processServerMessage(text: String) {
        try {
            val response = gson.fromJson(text, ServerResponse::class.java)

            // --- NEW: Handle SessionResumptionUpdate ---
            response.sessionResumptionUpdate?.let { update ->
                if (update.resumable == true && update.newHandle != null) {
                    sessionHandle = update.newHandle
                    // Persist the new handle for future sessions
                    getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).edit().putString("session_handle", sessionHandle).apply()
                    Log.i(TAG, "Session handle updated: ${sessionHandle?.take(10)}...")
                }
            }

            // --- NEW: Handle GoAway message ---
            response.goAway?.timeLeft?.let { timeLeft ->
                showError("Connection closing in $timeLeft. Will reconnect.")
            }

            // --- MODIFIED: Transcript Buffering Logic ---

            // 1. Handle server's translated text (output)
            val outputText = response.outputTranscription?.text ?: response.serverContent?.outputTranscription?.text
            if (outputText != null) {
                outputTranscriptBuffer.append(outputText)
            }
            
            // 2. Handle user's transcribed speech (input)
            val inputText = response.inputTranscription?.text ?: response.serverContent?.inputTranscription?.text
            if (inputText != null && inputText.isNotBlank()) {
                // First, flush the buffered output from the server's previous turn
                if (outputTranscriptBuffer.isNotEmpty()) {
                    translationAdapter.addOrUpdateTranslation(outputTranscriptBuffer.toString().trim(), false)
                    outputTranscriptBuffer.clear() // Clear the buffer
                }
                // Then, display the user's new input
                translationAdapter.addOrUpdateTranslation(inputText.trim(), true)
            }


            // 3. Handle audio playback (remains the same)
            response.serverContent?.modelTurn?.parts?.forEach { part ->
                part.inlineData?.data?.let { audioData ->
                    audioPlayer.playAudio(audioData)
                }
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
        if (::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        // When user stops talking, flush any remaining output from the buffer
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
        if (::audioHandler.isInitialized) {
            audioHandler.stopRecording()
        }
        webSocketClient?.disconnect()
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show() // Show for longer
        updateStatus("Alert: $message")
    }


    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                initializeComponentsDependentOnAudio()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun updateDisplayInfo() {
        val currentModelDisplayName = selectedModel
        val currentApiVersionDisplayName = selectedApiVersionObject?.displayName ?: "N/A"
        val currentApiKeyDisplayName = selectedApiKeyInfo?.displayName ?: "N/A"
        binding.configDisplay.text = "Using Model: $currentModelDisplayName | Version: $currentApiVersionDisplayName | Key: $currentApiKeyDisplayName"
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        teardownSession()
        mainScope.cancel()
    }
}
