package com.gemweblive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher // Import for ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts // Import for ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat // Use ContextCompat for checking permissions
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

    // In MainActivity.kt
    private val models = listOf(
        "gemini-live-2.5-flash-preview",
        "gemini-2.5-flash-preview-native-audio-dialog",
        "gemini-2.0-flash-live-001"
    )
    private var selectedModel = models[0] // Default to first model

    companion object {
        // REQUEST_RECORD_AUDIO_PERMISSION is no longer strictly needed with ActivityResultLauncher,
        // but can be kept for reference or if you mix old/new permission handling.
        // private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        private const val TAG = "MainActivity"
    }

    // Declare the ActivityResultLauncher for permissions
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Initialize the ActivityResultLauncher FIRST in onCreate
        // This registers the callback that handles the permission result.
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. You can now safely initialize audioHandler and start features.
                Log.d(TAG, "RECORD_AUDIO permission granted.")
                initializeComponentsDependentOnAudio() // Call the method to initialize audio-related components
            } else {
                // Permission is denied. Inform the user or disable microphone-related features.
                Log.w(TAG, "RECORD_AUDIO permission denied.")
                showError("Microphone permission is required for live streaming.")
                // Optionally, if the user denies permanently, you might guide them to settings.
                // For example: if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                //     showSettingsDialogForPermission()
                // }
            }
        }

        // 2. Now call checkPermissions, which will use the launcher
        checkPermissions()

        setupUI()
        // prepareNewClient() is now called from initializeComponentsDependentOnAudio()
        // or ensure it can run safely without audio components until permission is granted
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

    // New method to initialize components that require RECORD_AUDIO permission
    private fun initializeComponentsDependentOnAudio() {
        if (!::audioHandler.isInitialized) {
            audioHandler = AudioHandler(this) { audioData ->
                if (webSocketClient.isReady()) {
                    webSocketClient.sendAudio(audioData)
                }
            }
        }
        // Move prepareNewClient here if WebSocketClient also implicitly depends on permissions
        // or ensure prepareNewClient is structured to handle permission absence gracefully.
        // For simplicity, let's assume it should run after permissions are sorted.
        prepareNewClient()
    }


    private fun prepareNewClient() {
        // Ensure webSocketClient is only initialized once or re-initialized correctly
        if (::webSocketClient.isInitialized && webSocketClient.isConnected()) {
            webSocketClient.disconnect() // Disconnect existing client if any
        }

        val sharedPrefs = getSharedPreferences("GemWebLivePrefs", MODE_PRIVATE)
        val selectedApiVersion = "v1alpha" // Or retrieve from prefs as before

        webSocketClient = WebSocketClient(
            applicationContext, // Using applicationContext for MessageProcessor as well
            model = selectedModel,
            vadSilenceMs = getVadSensitivity(),
            apiVersion = selectedApiVersion,
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
        // Always check permissions before initiating a new connection if it needs audio
        // or starting listening.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions() // Re-request if somehow revoked
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
        // Redundant check here since handleMasterButton already checks.
        // However, it's harmless to keep as a defensive measure.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions() // Re-request if somehow revoked
            isListening = false // Ensure state is correct
            return
        }
        // audioHandler is initialized in initializeComponentsDependentOnAudio()
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
            prepareNewClient() // Prepare a new client for potential reconnect
            if (reconnect) {
                connect() // Immediately try to connect if reconnect is true
            }
        }
    }

    private fun updateUI() {
        binding.modelSpinner.isEnabled = !isSessionActive
        binding.settingsBtn.text = if (isSessionActive) "Disconnect" else "Settings"

        if (!isSessionActive) {
            binding.micBtn.text = "Connect"
            // Mic button should only be enabled if permissions are granted for initial connection
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

    // Refactored checkPermissions to use ActivityResultLauncher
    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted. Proceed with initializing audio components.
                Log.d(TAG, "RECORD_AUDIO permission already granted.")
                initializeComponentsDependentOnAudio()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show an explanation to the user why you need the permission.
                // This is a good place to use a Dialog or a SnackBar.
                Log.i(TAG, "Showing rationale for RECORD_AUDIO permission.")
                Toast.makeText(this, "Microphone access is essential for voice input.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                // No explanation needed, request the permission directly.
                Log.d(TAG, "Requesting RECORD_AUDIO permission.")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // onRequestPermissionsResult is no longer needed for RECORD_AUDIO with ActivityResultLauncher
    // You can remove this method or adapt it if you have other permissions still using it.
    // override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    //     super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    //     if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
    //         showError("Audio permission required")
    //     }
    // }

    override fun onDestroy() {
        super.onDestroy()
        teardownSession() // Ensure session is torn down and resources released
        mainScope.cancel() // Cancel all coroutines started by mainScope
    }
}
