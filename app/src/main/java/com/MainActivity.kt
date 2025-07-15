package com.gemweblive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gemweblive.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import com.gemweblive.ApiVersion
import com.gemweblive.ApiKeyInfo

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

    // Declare the lists and selected objects as class properties
    private val models = listOf(
        "gemini-live-2.5-flash-preview",
        "gemini-2.5-flash-preview-native-audio-dialog",
        "gemini-2.0-flash-live-001"
    )
    // No longer directly selected here, but loaded from prefs
    private var selectedModel: String = models[0] // Default for initial load if no prefs

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

        // Load API versions and keys from resources when the activity is created
        loadApiVersionsFromResources(this)
        loadApiKeysFromResources(this)

        // NEW: Load selected model from preferences
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]


        // NEW LOGGING: Check loaded lists immediately after loading
        Log.d(TAG, "Loaded API Versions: ${apiVersions.size} items. Selected: ${selectedApiVersionObject?.displayName} (${selectedApiVersionObject?.value})")
        Log.d(TAG, "Loaded API Keys: ${apiKeys.size} items. Selected: ${selectedApiKeyInfo?.displayName} (${selectedApiKeyInfo?.value})")
        Log.d(TAG, "Loaded Model: $selectedModel") // NEW Log for model


        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                initializeComponentsDependentOnAudio()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                showError("Microphone permission is required for live streaming.")
            }
        }

        checkPermissions()

        setupUI()
        updateDisplayInfo() // NEW: Update display info on startup
    }

    // --- loadApiVersionsFromResources method (remains as last provided, with warning change) ---
    private fun loadApiVersionsFromResources(context: Context) {
        val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
        val parsedList = mutableListOf<ApiVersion>()

        for (itemString in rawApiVersions) {
            val parts = itemString.split("|", limit = 2)

            if (parts.size == 2) {
                parsedList.add(ApiVersion(parts[0].trim(), parts[1].trim()))
            } else {
                Log.w(TAG, "API version item in resources: '$itemString' does not contain '|'. Using as DisplayName|Value.")
                parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
            }
        }
        apiVersions = parsedList

        val currentApiVersionValue = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_version", null)
        selectedApiVersionObject = parsedList.firstOrNull { it.value == currentApiVersionValue } ?: parsedList.firstOrNull()
        
        if (selectedApiVersionObject == null && apiVersions.isNotEmpty()) {
            selectedApiVersionObject = apiVersions[0]
            Log.d(TAG, "loadApiVersions: Defaulted selectedApiVersionObject to first item: ${selectedApiVersionObject?.value}")
        }
        Log.d(TAG, "loadApiVersions: Loaded ${apiVersions.size} items. Initial selected: ${selectedApiVersionObject?.value}")
    }

    // --- loadApiKeysFromResources method (remains as last provided) ---
    private fun loadApiKeysFromResources(context: Context) {
        val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
        val parsedList = mutableListOf<ApiKeyInfo>()

        for (itemString in rawApiKeys) {
            val parts = itemString.split(":", limit = 2)

            if (parts.size == 2) {
                val displayName = parts[0].trim()
                val value = parts[1].trim()
                parsedList.add(ApiKeyInfo(displayName, value))
            } else {
                Log.e(TAG, "Malformed API key item in resources: '$itemString'. Expected 'DisplayName:Value' format.")
            }
        }
        apiKeys = parsedList
        val currentApiKeyValue = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getString("api_key", null)
        selectedApiKeyInfo = parsedList.firstOrNull { it.value == currentApiKeyValue } ?: parsedList.firstOrNull()

        if (selectedApiKeyInfo == null && apiKeys.isNotEmpty()) {
            selectedApiKeyInfo = apiKeys[0]
            Log.d(TAG, "loadApiKeys: Defaulted selectedApiKeyInfo to first item: ${selectedApiKeyInfo?.value}")
        }
        Log.d(TAG, "loadApiKeys: Loaded ${apiKeys.size} items. Initial selected: ${selectedApiKeyInfo?.value?.take(5)}...")
    }


    private fun setupUI() {
        translationAdapter = TranslationAdapter()
        binding.transcriptLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = translationAdapter
        }

        // REMOVED: Model Spinner from main UI
        // REMOVED: API Version Spinner from main UI

        // NEW: Debug Connect Button Click Listener
        binding.debugConnectBtn.setOnClickListener { handleDebugConnectButton() }

        binding.micBtn.setOnClickListener { handleMasterButton() }
        binding.settingsBtn.setOnClickListener { handleSettingsDisconnectButton() }
        updateUI()
    }

    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                if (::webSocketClient.isInitialized && webSocketClient.isReady()) {
                    webSocketClient.sendAudio(audioData)
                }
            }
        }
        prepareNewClient()
    }

    private fun prepareNewClient() {
        if (::webSocketClient.isInitialized && webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }

        // Reload preferences to ensure latest saved values are used
        val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
        selectedApiVersionObject = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
        selectedApiKeyInfo = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()


        Log.d(TAG, "prepareNewClient: Using API Version: ${selectedApiVersionObject?.value ?: "fallback_v1alpha"}")
        Log.d(TAG, "prepareNewClient: Using API Key: ${selectedApiKeyInfo?.value?.take(5) ?: "fallback_empty"}...")
        Log.d(TAG, "prepareNewClient: Using Model: $selectedModel") // NEW log for model
        
        webSocketClient = WebSocketClient(
            context = applicationContext,
            model = selectedModel, // Use selectedModel from preferences
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1alpha",
            apiKey = selectedApiKeyInfo?.value ?: "",
            onOpen = {
                mainScope.launch {
                    isSessionActive = true
                    updateStatus("Connected, awaiting server...")
                    updateUI()
                    Log.d(TAG, "WebSocket onOpen callback received. isSessionActive=$isSessionActive")
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
                    Log.e(TAG, "WebSocket onFailure callback received.", t)
                }
            },
            onSetupComplete = {
                mainScope.launch {
                    isServerReady = true
                    updateStatus("Ready to listen")
                    updateUI()
                    Log.d(TAG, "WebSocket onSetupComplete callback received. isServerReady=$isServerReady")
                }
            }
        )
    }

    // NEW: Handler for the Debug Connect Button
    private fun handleDebugConnectButton() {
        Log.d(TAG, "handleDebugConnectButton: Debug Connect clicked. Forcing connection attempt.")
        // Directly call connect() to bypass the permission check for the button's action
        connect()
    }


    private fun handleMasterButton() {
        Log.d(TAG, "handleMasterButton: Clicked. isSessionActive=$isSessionActive")
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
        // Pass the models list to the SettingsDialog
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE), models)
        dialog.setOnDismissListener {
            // This listener is called when the dialog is dismissed (e.g., Save button clicked)
            // Reload preferences and update UI to reflect new settings
            val prefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
            selectedModel = prefs.getString("selected_model", models[0]) ?: models[0]
            selectedApiVersionObject = apiVersions.firstOrNull { it.value == prefs.getString("api_version", null) } ?: apiVersions.firstOrNull()
            selectedApiKeyInfo = apiKeys.firstOrNull { it.value == prefs.getString("api_key", null) } ?: apiKeys.firstOrNull()
            updateDisplayInfo() // Update the main screen's config display
            if (isSessionActive) {
                Toast.makeText(this, "Settings saved. Please Disconnect and Connect to apply.", Toast.LENGTH_LONG).show()
                teardownSession(reconnect = true) // Optionally reconnect immediately
            }
        }
        dialog.show()
    }

    private fun getVadSensitivity(): Int {
        return getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
    }

    private fun connect() {
        Log.d(TAG, "connect: Attempting connection. isSessionActive=$isSessionActive")
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
        // REMOVED: Model Spinner enablement
        // REMOVED: API Version Spinner enablement
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"

        if (!isSessionActive) {
            binding.micBtn.text = "Connect"
            binding.micBtn.isEnabled = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // Debug button always enabled when not active session
            binding.debugConnectBtn.isEnabled = true
        } else {
            binding.micBtn.isEnabled = isServerReady
            binding.micBtn.text = when {
                !isServerReady -> "Connecting..."
                isListening -> "Stop"
                else -> "Start Listening"
            }
            binding.debugConnectBtn.isEnabled = false // Disable debug button once session is active
        }
        binding.interimDisplay.visibility = if (isListening) View.VISIBLE else View.GONE
        Log.d(TAG, "updateUI: isSessionActive=$isSessionActive, isServerReady=$isServerReady, micBtn.isEnabled=${binding.micBtn.isEnabled}, debugConnectBtn.isEnabled=${binding.debugConnectBtn.isEnabled}")
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        updateStatus(message)
    }

    private fun checkPermissions() {
        Log.d(TAG, "checkPermissions: Checking RECORD_AUDIO permission.")
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "RECORD_AUDIO permission already granted.")
                initializeComponentsDependentOnAudio()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Log.i(TAG, "Showing rationale for RECORD_AUDIO permission.")
                Toast.makeText(this, "Microphone access is essential for voice input.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                Log.d(TAG, "Requesting RECORD_AUDIO permission.")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // NEW FUNCTION: To update the main screen's configuration display
    private fun updateDisplayInfo() {
        val currentModelDisplayName = selectedModel
        val currentApiVersionDisplayName = selectedApiVersionObject?.displayName ?: "N/A"
        val currentApiKeyDisplayName = selectedApiKeyInfo?.displayName ?: "N/A"

        binding.configDisplay.text = "Using Model: $currentModelDisplayName | Version: $currentApiVersionDisplayName | Key: $currentApiKeyDisplayName"
    }


    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        mainScope.cancel()
    }
}
