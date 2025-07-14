package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File // Add this import
import java.io.FileWriter // Add this import
import java.io.PrintWriter // Add this import
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val context: Context,
    private val model: String,
    private val vadSilenceMs: Int,
    private val onOpen: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onClosing: (Int, String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onSetupComplete: () -> Unit
) {
    private var webSocket: WebSocket? = null
    @Volatile private var isSetupComplete = false
    @Volatile private var isConnected = false

    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    
    private var logFileWriter: PrintWriter? = null // Add this
    private lateinit var logFile: File // Add this
        
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive
        .addInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger { // Custom Logger
            override fun log(message: String) {
                Log.d(TAG, message) // Log to Logcat
                logFileWriter?.println(message) // Log to file
            }
        }).apply {
            level = HttpLoggingInterceptor.Level.BODY // Change to BODY for full request/response
        })
        .build()

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val API_KEY = "AIzaSyCKfh1rvPHkyxHcNrJylcXjck6wRxmizAc" // Replace with your actual API key
        private const val TAG = "WebSocketClient"
    }

private fun sendConfigMessage() {
    try {
        val config = mapOf(
            "setup" to mapOf(
                "model" to "models/$model",
                "generation_config" to mapOf(
                    "response_modalities" to listOf("AUDIO")
                ),
                "input_audio_transcription" to emptyMap<String, Any>(),
                "output_audio_transcription" to emptyMap<String, Any>(),
                "system_instruction" to mapOf(
                    "parts" to listOf(
                        mapOf("text" to """
          
                    )
                ),
                "realtime_input_config" to mapOf(
                    "automatic_activity_detection" to mapOf(
                        "silence_duration_ms" to vadSilenceMs
                    )
                )
            )
        )
        
        val configString = gson.toJson(config)
        Log.d(TAG, "Sending config: $configString")
        webSocket?.send(configString)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to send config", e)
    }
}
    fun connect() {
        if (isConnected) return
        Log.i(TAG, "Attempting to connect...")

        try {
            // Initialize log file
            val logDir = File(context.cacheDir, "http_logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "network_log_${System.currentTimeMillis()}.txt")
            logFileWriter = PrintWriter(FileWriter(logFile, true), true) // 'true' for auto-flush
            logFileWriter?.println("--- New Session Log: ${java.util.Date()} ---")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
            onFailure(e)
            return
        }

        
        val request = Request.Builder()
            .url("wss://$HOST/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$API_KEY")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    Log.i(TAG, "WebSocket connection opened")
                    isConnected = true
                    sendConfigMessage()
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    Log.d(TAG, "Server message: ${text.take(500)}...")
                    try {
                        val response = gson.fromJson(text, Map::class.java)
                        when {
                            response.containsKey("setupComplete") -> {
                                if (!isSetupComplete) {
                                    isSetupComplete = true
                                    Log.i(TAG, "Server setup complete")
                                    onSetupComplete()
                                }
                            }
                            else -> onMessage(text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing server message", e)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    Log.w(TAG, "WebSocket closing: $code - $reason")
                    cleanup()
                    onClosing(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    Log.e(TAG, "WebSocket failure", t)
                    cleanup()
                    onFailure(t)
                }
            }
        })
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isReady()) return
        scope.launch {
            try {
                val realtimeInput = mapOf(
                    "realtimeInput" to mapOf(
                        "audio" to mapOf(
                            "data" to Base64.encodeToString(audioData, Base64.NO_WRAP),
                            "mime_type" to "audio/pcm;rate=16000"
                        )
                    )
                )
                webSocket?.send(gson.toJson(realtimeInput))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send audio", e)
            }
        }
    }
    
    fun disconnect() {
        scope.launch {
            cleanup()
        }
    }

    private fun cleanup() {
        if (isConnected) {
            Log.i(TAG, "Cleaning up WebSocket connection")
            webSocket?.close(1000, "Normal closure")
            webSocket = null
        }
        logFileWriter?.close() // Close the log file writer
        logFileWriter = null
        isConnected = false
        isSetupComplete = false
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
}
