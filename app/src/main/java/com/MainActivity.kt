package com.gemweblive

import android.Manifest
import android.content.Context // Added import for Context
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
import com.gemweblive.ApiVersion //
import com.gemweblive.ApiKeyInfo //

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
    private var selectedModel = models[0] // Default to first model

    // NEW: Declare lists for API versions and keys, and their selected objects
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
        loadApiVersionsFromResources(this) // Pass context to resource loading functions
        loadApiKeysFromResources(this) // Pass context

        // NEW LOGGING: Check loaded lists immediately after loading
        Log.d(TAG, "Loaded API Versions: ${apiVersions.size} items. Selected: ${selectedApiVersionObject?.displayName} (${selectedApiVersionObject?.value})")
        Log.d(TAG, "Loaded API Keys: ${apiKeys.size} items. Selected: ${selectedApiKeyInfo?.displayName} (${selectedApiKeyInfo?.value})")

        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                initializeComponentsDependentOnAudio()
            } else {
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                showError("Microphone permission is required for live streaming.")
            }
        }

        checkPermissions() // This will now correctly check permissions and initialize components

        setupUI()
    }

    // NEW: Methods to load API versions and keys from resources
private fun loadApiVersionsFromResources(context: Context) {
    val rawApiVersions = context.resources.getStringArray(R.array.api_versions)
    val parsedList = mutableListOf<ApiVersion>()

    for (itemString in rawApiVersions) {
        // Since your strings.xml for api_versions has NO delimiter (e.g., <item>v1alpha</item>),
        // we'll use the entire string for both display and value.
        parsedList.add(ApiVersion(itemString.trim(), itemString.trim()))
    }

    // Assign to the correct list property based on the class instance
    if (this is MainActivity) {
        apiVersions = parsedList
    } else if (this is SettingsDialog) {
        apiVersionsList = parsedList
    }

    // Set initial selected version based on saved preference or first item
    val sharedPrefs = if (this is MainActivity) getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE) else prefs
    val currentApiVersionValue = sharedPrefs.getString("api_version", null)

    val selectedObject = parsedList.firstOrNull { it.value == currentApiVersionValue } ?: parsedList.firstOrNull()
    
    if (this is MainActivity) {
        selectedApiVersionObject = selectedObject
        if (selectedApiVersionObject == null && apiVersions.isNotEmpty()) {
            selectedApiVersionObject = apiVersions[0] // Fallback
        }
        Log.d(TAG, "loadApiVersions: Loaded ${apiVersions.size} items. Initial selected: ${selectedApiVersionObject?.value}")
    } else if (this is SettingsDialog) {
        selectedApiVersion = selectedObject
        if (selectedApiVersion == null && apiVersionsList.isNotEmpty()) {
            selectedApiVersion = apiVersionsList[0] // Fallback
        }
        Log.d(TAG, "loadApiVersions: (Dialog) Loaded ${apiVersionsList.size} items. Initial selected: ${selectedApiVersion?.value}")
    }
}

private fun loadApiKeysFromResources(context: Context) {
    val rawApiKeys = context.resources.getStringArray(R.array.api_keys)
    val parsedList = mutableListOf<ApiKeyInfo>()

    for (itemString in rawApiKeys) {
        // CORRECTED: Split by colon (:) to match your strings.xml format
        val parts = itemString.split(":", limit = 2)

        if (parts.size == 2) {
            val displayName = parts[0].trim()
            val value = parts[1].trim()
            parsedList.add(ApiKeyInfo(displayName, value))
        } else {
            Log.e(TAG, "Malformed API key item in resources: '$itemString'. Expected 'DisplayName:Value' format.")
        }
    }
    // Assign to the correct list property based on the class instance
    if (this is MainActivity) {
        apiKeys = parsedList
    } else if (this is SettingsDialog) {
        apiKeysList = parsedList
    }

    // Set initial selected API key based on saved preference or first item
    val sharedPrefs = if (this is MainActivity) getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE) else prefs
    val currentApiKeyValue = sharedPrefs.getString("api_key", null)

    val selectedObject = parsedList.firstOrNull { it.value == currentApiKeyValue } ?: parsedList.firstOrNull()

    if (this is MainActivity) {
        selectedApiKeyInfo = selectedObject
        if (selectedApiKeyInfo == null && apiKeys.isNotEmpty()) {
            selectedApiKeyInfo = apiKeys[0] // Fallback
        }
        Log.d(TAG, "loadApiKeys: Loaded ${apiKeys.size} items. Initial selected: ${selectedApiKeyInfo?.value?.take(5)}...")
    } else if (this is SettingsDialog) {
        selectedApiKeyInfo = selectedObject
        if (selectedApiKeyInfo == null && apiKeysList.isNotEmpty()) {
            selectedApiKeyInfo = apiKeysList[0] // Fallback
        }
        Log.d(TAG, "loadApiKeys: (Dialog) Loaded ${apiKeysList.size} items. Initial selected: ${selectedApiKeyInfo?.value?.take(5)}...")
    }
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

        // NEW: Setup for API Version Spinner
        // Ensure binding.apiVersionSpinner exists in activity_main.xml
        binding.apiVersionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, apiVersions)
        selectedApiVersionObject?.let { initialSelection ->
            binding.apiVersionSpinner.setSelection(apiVersions.indexOf(initialSelection))
        }

        binding.apiVersionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newlySelectedApiVersionObject = apiVersions[position]
                if (selectedApiVersionObject != newlySelectedApiVersionObject) {
                    selectedApiVersionObject = newlySelectedApiVersionObject
                    Log.d(TAG, "Selected API Version: ${selectedApiVersionObject?.displayName} (${selectedApiVersionObject?.value})")
                    if (isSessionActive) {
                        Toast.makeText(this@MainActivity, "Changing API version requires reconnect", Toast.LENGTH_SHORT).show()
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

    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                // Ensure webSocketClient is initialized before trying to use it
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

        Log.d(TAG, "prepareNewClient: Using API Version: ${selectedApiVersionObject?.value ?: "fallback_v1alpha"}")
        Log.d(TAG, "prepareNewClient: Using API Key: ${selectedApiKeyInfo?.value?.take(5) ?: "fallback_empty"}...") // Log first 5 chars of key
        
        // Use selected objects directly
        webSocketClient = WebSocketClient(
            context = applicationContext,
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersionObject?.value ?: "v1alpha", // Use the value from selected object
            apiKey = selectedApiKeyInfo?.value ?: "", // Use the value from selected object
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
        Log.d(TAG, "handleMasterButton: Clicked. isSessionActive=$isSessionActive") // NEW LOG
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
        val dialog = SettingsDialog(this, getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE))
        dialog.show()
    }

    private fun getVadSensitivity(): Int {
        return getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE).getInt("vad_sensitivity_ms", 800)
    }

    private fun connect() {
        Log.d(TAG, "connect: Attempting connection. isSessionActive=$isSessionActive") // NEW LOG
        if (isSessionActive) return
        updateStatus("Connecting...")
        updateUI()
        webSocketClient.connect()
    }

    private fun processServerMessage(text: String) {
        // Log the raw message to file using the MessageProcessor class
        // Assuming your WebSocketClient now uses a MessageProcessor internally
        // (as discussed in previous steps), this part is already handled there.
        // You just parse the JSON and update UI here.

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
        binding.modelSpinner.isEnabled = !isSessionActive
        binding.apiVersionSpinner.isEnabled = !isSessionActive // NEW: Enable/disable API version spinner
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"

        if (!isSessionActive) {
            binding.micBtn.text = "Connect"
            binding.micBtn.isEnabled = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
        Log.d(TAG, "checkPermissions: Checking RECORD_AUDIO permission.") // NEW LOGGING
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

    override fun onDestroy() {
        super.onDestroy()
        teardownSession()
        mainScope.cancel()
    }
}
