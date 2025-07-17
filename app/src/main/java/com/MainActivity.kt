package com.BWCTrans

// --- IMPORTS ---
import android.Manifest
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
import com.BWCTrans.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.Response
import java.lang.StringBuilder


// --- DATA CLASSES ---
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
    @SerializedName("outputTranscription") val outputTranscription: Transcription?,
    @SerializedName("turnComplete") val turnComplete: Boolean? // Added to capture turn completion
)
data class ModelTurn(@SerializedName("parts") val parts: List<Part>?)
data class Part(@SerializedName("text") val text: String?, @SerializedName("inlineData") val inlineData: InlineData?)
data class InlineData(@SerializedName("mime_type") val mimeType: String?, @SerializedName("data") val data: String?)
data class Transcription(@SerializedName("text") val text: String?)
data class SetupComplete(val dummy: String? = null)
data class SessionResumptionUpdate(@SerializedName("newHandle") val newHandle: String?, @SerializedName("resumable") val resumable: Boolean?)
data class GoAway(@SerializedName("timeLeft") val timeLeft: String?)

class MainActivity : AppCompatActivity() {

    // --- COMPANION OBJECT ---
    companion object {
        private const val TAG = "MainActivity"
    }

    // --- PROPERTIES & VIEWS ---
    private lateinit var binding: ActivityMainBinding
    private lateinit var audioHandler: AudioHandler
    private var webSocketClient: WebSocketClient? = null
    private lateinit var translationAdapter: TranslationAdapter
    private lateinit var audioPlayer: AudioPlayer
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val gson = Gson()
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>


    // --- STATE & CONFIGURATION ---
    private var sessionHandle: String? = null
    private val outputTranscriptBuffer = StringBuilder()
    @Volatile private var isListening = false
    @Volatile private var isSessionActive = false
    @Volatile private var isServerReady = false

    private val models = listOf("gemini-2.5-flash-preview-native-audio-dialog", "gemini-2.0-flash-live-001", "gemini-2.5-flash-live-preview")
    private var selectedModel: String = models[0]
    private var apiVersions: List<ApiVersion> = emptyList()
    private var apiKeys: List<ApiKeyInfo> = emptyList()
    private var selectedApiVersionObject: ApiVersion? = null
    private var selectedApiKeyInfo: ApiKeyInfo? = null

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5


    // --- ACTIVITY LIFECYCLE ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: Activity created.")

        loadApiVersionsFromResources()
        loadApiKeysFromResources()
        loadPreferences()

        audioPlayer = AudioPlayer()
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i(TAG, "RECORD_AUDIO permission granted.")
                initializeComponentsDependentOnAudio()
            } else {
                Log.e(TAG, "RECORD_AUDIO permission denied.")
                showError("Microphone permission is required.")
            }
        }

        checkPermissions()
        setupUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Activity is being destroyed.")
        audioPlayer.release()
        teardownSession()
        mainScope.cancel()
    }


    // --- INITIALIZATION & SETUP ---
    private fun setupUI() {
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        translationAdapter = TranslationAdapter()
        binding.transcriptLog.layoutManager = LinearLayoutManager(this)
        binding.transcriptLog.adapter = translationAdapter

        binding.settingsBtn.setOnClickListener {
            val userSettingsDialog = UserSettingsDialogFragment()
            userSettingsDialog.show(supportFragmentManager, "UserSettingsDialog")
        }

        binding.debugSettingsBtn.setOnClickListener {
            // This now opens your ORIGINAL developer settings dialog
            val devSettingsDialog = SettingsDialog(this, this, getSharedPreferences("BWCTransPrefs", MODE_PRIVATE), models)
            devSettingsDialog.setOnDismissListener {
                Log.d(TAG, "Developer SettingsDialog dismissed.")
                loadPreferences()
                if (isSessionActive) {
                    Toast.makeText(this, "Dev settings saved. Reconnect to apply.", Toast.LENGTH_LONG).show()
                }
            }
            devSettingsDialog.show()
        }

        binding.micBtn.setOnClickListener {
            Log.d(TAG, "Mic button clicked.")
            handleMasterButton()
        }

        binding.historyBtn.setOnClickListener {
            Toast.makeText(this, "History view coming soon!", Toast.LENGTH_SHORT).show()
        }

        updateUI()
        Log.d(TAG, "setupUI: All new UI components initialized.")
    }

    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                webSocketClient?.sendAudio(audioData)
            }
            Log.i(TAG, "AudioHandler initialized.")
        }
        prepareNewClient()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("BWCTransPrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
        sessionHandle = prefs.getString("session_handle", null)
        Log.d(TAG, "loadPreferences: Loaded model '$selectedModel' and session handle '$sessionHandle'")
    }

    private fun loadApiVersionsFromResources() {
        val rawApiVersions = resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()
        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)
            parsedList.add(if (parts.size == 2) ApiVersion(parts[0].trim(), parts[1].trim()) else ApiVersion(itemString.trim(), itemString.trim()))
        }
        apiVersions = parsedList
        selectedApiVersionObject = parsedList.firstOrNull { it.value == getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getString("api_version", null) } ?: parsedList.firstOrNull()
        Log.d(TAG, "loadApiVersionsFromResources: Loaded ${apiVersions.size} API versions. Selected: ${selectedApiVersionObject?.displayName}")
    }

    private fun loadApiKeysFromResources() {
        val rawApiKeys = resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()
        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)
            if (parts.size == 2) parsedList.add(ApiKeyInfo(parts[0].trim(), parts[1].trim()))
        }
        apiKeys = parsedList
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getString("api_key", null) } ?: apiKeys.firstOrNull()
        Log.d(TAG, "loadApiKeysFromResources: Loaded ${apiKeys.size} API keys. Selected: ${selectedApiKeyInfo?.displayName}")
    }

    private fun prepareNewClient() {
        webSocketClient?.disconnect()
        loadPreferences()
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getString("api_version", null) } ?: apiVersions.firstOrNull()
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getString("api_key", null) } ?: apiKeys.firstOrNull()

        webSocketClient = WebSocketClient(
            context = applicationContext,
            modelName = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1beta",
            apiKey = selectedApiKeyInfo?.value ?: "",
            sessionHandle = sessionHandle,
            onOpen = { mainScope.launch {
                Log.i(TAG, "WebSocket onOpen callback received.")
                isSessionActive = true
                reconnectAttempts = 0
                updateStatus("Connected, configuring server...")
                updateUI()
            } },
            onMessage = { text -> mainScope.launch { processServerMessage(text) } },
            onClosing = { code, reason -> mainScope.launch {
                Log.w(TAG, "WebSocket onClosing callback received: Code=$code, Reason=$reason")
                teardownSession(reconnect = true)
            } },
            onFailure = { t, response -> mainScope.launch {
                Log.e(TAG, "WebSocket onFailure callback received.", t)
                var errorMessage = "Connection error: ${t.message}"
                if (response != null) {
                    errorMessage += "\n(Code: ${response.code})"
                    if (response.code == 404) {
                        errorMessage = "Error: The server endpoint was not found (404). Please check the API version and key."
                    }
                }
                showError(errorMessage)
                teardownSession(reconnect = true)
            } },
            onSetupComplete = { mainScope.launch {
                Log.i(TAG, "WebSocket onSetupComplete callback received.")
                isServerReady = true
                updateStatus("Ready to listen")
                updateUI()
            } }
        )
        Log.i(TAG, "New WebSocketClient prepared.")
    }


    // --- PERMISSION HANDLING ---
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "checkPermissions: RECORD_AUDIO permission already granted.")
            initializeComponentsDependentOnAudio()
        } else {
            Log.i(TAG, "checkPermissions: Requesting RECORD_AUDIO permission.")
            // --- MODIFIED: Show a more informative initial Toast ---
            Toast.makeText(this, "Microphone permission is needed for the translator.", Toast.LENGTH_LONG).show()
            requestAudioPermission()
        }
    }

    fun requestAudioPermission() {
        Log.i(TAG, "requestAudioPermission: Explicitly requesting audio permission.")
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }


    // --- UI & EVENT HANDLERS ---
    private fun handleMasterButton() {
        if (!isServerReady && !isSessionActive) {
            Log.d(TAG, "handleMasterButton: No active session, connecting.")
            connect()
            return
        }
        if (!isServerReady) {
            Log.w(TAG, "handleMasterButton: Server not ready, ignoring.")
            return
        }

        isListening = !isListening
        Log.i(TAG, "handleMasterButton: Toggling listening state to: $isListening")
        if (isListening) {
            startAudio()
        } else {
            stopAudio()
        }
        updateUI()
    }

    private fun showSettingsDialog() {
        val userSettingsDialog = UserSettingsDialogFragment()
        userSettingsDialog.show(supportFragmentManager, "UserSettingsDialog")
    }


    // --- CORE WEBSOCKET & AUDIO LOGIC ---
    private fun connect() {
        if (isSessionActive) {
            Log.w(TAG, "connect: Already connected or connecting.")
            return
        }
        reconnectAttempts = 0
        Log.i(TAG, "connect: Attempting to establish WebSocket connection.")
        updateStatus("Connecting...")
        updateUI()
        webSocketClient?.connect()
    }

    private fun teardownSession(reconnect: Boolean = false) {
        if (!isSessionActive) return
        Log.w(TAG, "teardownSession: Tearing down session. Reconnect: $reconnect")
        isListening = false
        isSessionActive = false
        isServerReady = false
        if (::audioHandler.isInitialized) audioHandler.stopRecording()
        webSocketClient?.disconnect()
        mainScope.launch {
            if (!reconnect) updateStatus("Disconnected")
            updateUI()
            prepareNewClient()
            if (reconnect && reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++
                val delayMillis = (1000 * Math.pow(2.0, reconnectAttempts.toDouble())).toLong()
                Log.i(TAG, "Attempting to reconnect in ${delayMillis / 1000} seconds. (Attempt $reconnectAttempts)")
                updateStatus("Connection lost. Reconnecting in ${delayMillis / 1000}s...")
                delay(delayMillis)
                connect()
            } else if (reconnect) {
                Log.e(TAG, "Max reconnect attempts reached. Will not reconnect.")
                showError("Could not establish a connection. Please try again later.")
                reconnectAttempts = 0
            }
        }
    }

    private fun processServerMessage(text: String) {
        Log.v(TAG, "processServerMessage: Received raw message: ${text.take(500)}...")
        try {
            val response = gson.fromJson(text, ServerResponse::class.java)

            response.sessionResumptionUpdate?.let {
                if (it.resumable == true && it.newHandle != null) {
                    sessionHandle = it.newHandle
                    getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).edit().putString("session_handle", sessionHandle).apply()
                    Log.i(TAG, "Session handle updated and saved.")
                }
            }
            response.goAway?.timeLeft?.let {
                Log.w(TAG, "Received GO_AWAY message. Time left: $it. Will reconnect.")
                showError("Connection closing in $it. Will reconnect.")
            }

            val outputText = response.outputTranscription?.text ?: response.serverContent?.outputTranscription?.text
            if (outputText != null) {
                outputTranscriptBuffer.append(outputText)
            }

            val inputText = response.inputTranscription?.text ?: response.serverContent?.inputTranscription?.text
            if (inputText != null && inputText.isNotBlank()) {
                if (outputTranscriptBuffer.isNotEmpty()) {
                    val fullTranslation = outputTranscriptBuffer.toString().trim()
                    Log.d(TAG, "Displaying full translation: '$fullTranslation'")
                    translationAdapter.addOrUpdateTranslation(fullTranslation, false)
                    outputTranscriptBuffer.clear()
                }
                Log.d(TAG, "Displaying user input: '$inputText'")
                translationAdapter.addOrUpdateTranslation(inputText.trim(), true)
            }
            response.serverContent?.modelTurn?.parts?.forEach { part ->
                part.inlineData?.data?.let {
                    Log.d(TAG, "Playing received audio chunk.")
                    audioPlayer.playAudio(it)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: $text", e)
        }
    }

    private fun startAudio() {
        if (!::audioHandler.isInitialized) {
            Log.d(TAG, "startAudio: Initializing audio components first.")
            initializeComponentsDependentOnAudio()
        }
        Log.i(TAG, "startAudio: Starting audio recording.")
        audioHandler.startRecording()
        updateStatus("Listening...")
    }

    private fun stopAudio() {
        if (::audioHandler.isInitialized) {
            Log.i(TAG, "stopAudio: Stopping audio recording.")
            audioHandler.stopRecording()
        }
        if (outputTranscriptBuffer.isNotEmpty()) {
            val finalTranslation = outputTranscriptBuffer.toString().trim()
            Log.d(TAG, "Displaying final buffered translation: '$finalTranslation'")
            translationAdapter.addOrUpdateTranslation(finalTranslation, false)
            outputTranscriptBuffer.clear()
        }
        updateStatus("Ready to listen")
    }


    // --- DIALOG INTERFACE IMPLEMENTATIONS ---
    override fun onRequestPermission() {
        requestAudioPermission()
    }

    override fun onForceConnect() {
        Log.i(TAG, "onForceConnect: Forcing reconnection.")
        Toast.makeText(this, "Forcing reconnection...", Toast.LENGTH_SHORT).show()
        teardownSession()
        // Add a small delay before connecting to ensure resources are released
        mainScope.launch {
            delay(500)
            connect()
        }
    }


    // --- HELPER & UTILITY FUNCTIONS ---
    private fun getVadSensitivity(): Int {
        val sensitivity = getSharedPreferences("BWCTransPrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
        Log.d(TAG, "getVadSensitivity: VAD sensitivity is $sensitivity ms.")
        return sensitivity
    }

    private fun updateUI() {
        binding.micBtn.setImageResource(if (isListening) R.drawable.ic_stop else R.drawable.ic_mic)

        binding.statusText.text = when {
            !isSessionActive -> "Status: Disconnected"
            !isServerReady -> "Status: Connecting..."
            isListening -> "Listening..."
            else -> ""
        }
        binding.infoText.visibility = if (translationAdapter.itemCount == 0) View.VISIBLE else View.GONE
        binding.debugSettingsBtn.isEnabled = !isSessionActive
        binding.micBtn.isEnabled = (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
        Log.i(TAG, "Status Updated: $message")
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus("Alert: $message")
        Log.e(TAG, "showError: $message")
    }
}
