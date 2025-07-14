package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val context: Context,
    private val model: String,
    private val vadSilenceMs: Int,
    private val apiVersion: String,
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

    private var logFileWriter: PrintWriter? = null
    private lateinit var logFile: File

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                Log.d(TAG, message)
                logFileWriter?.println(message)
            }
        }).apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val API_KEY = "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8"
        private const val TAG = "WebSocketClient"

        // TEMPORARILY SIMPLIFIED SYSTEM INSTRUCTION FOR TESTING ENCODING
        // This constant is still here, but it won't be used in the config for this test.
        private val SYSTEM_INSTRUCTION_TEXT = """
            You are a helpful assistant. Translate between English and Thai.
            Be direct and concise.
        """.trimIndent()
    }

    private fun sendConfigMessage() {
        try {
            val config = mapOf(
                "setup" to mapOf(
                    "model" to "models/$model",
                    "generationConfig" to mapOf(
                        "response_modalities" to listOf("AUDIO") // FIX: Corrected to 'response_modalities' (plural)
                    ),
                    // TEMPORARILY REMOVED systemInstruction for testing
                    "realtimeInputConfig" to mapOf(
                        "automaticActivityDetection" to mapOf(
                            "silence_duration_ms" to vadSilenceMs
                        )
                    )
                )
            )

            val configString = gson.toJson(config)
            Log.d(TAG, "Sending config (length: ${configString.length}): $configString")
            logFileWriter?.println("OUTGOING CONFIG: $configString")
            webSocket?.send(configString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config", e)
        }
    }

    fun connect() {
        if (isConnected) return
        Log.i(TAG, "Attempting to connect...")

        try {
            val logDir = File(context.getExternalFilesDir(null), "http_logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "network_log_${System.currentTimeMillis()}.txt")
            logFileWriter = PrintWriter(FileWriter(logFile, true), true)
            logFileWriter?.println("--- New Session Log: ${java.util.Date()} ---")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
            onFailure(e)
            return
        }

        val request = Request.Builder()
            .url("wss://$HOST/ws/google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent?key=$API_KEY")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    Log.i(TAG, "WebSocket connection opened")
                    logFileWriter?.println("WEB_SOCKET_OPENED")
                    isConnected = true
                    sendConfigMessage()
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    Log.d(TAG, "Server message: ${text.take(500)}...")
                    logFileWriter?.println("INCOMING MESSAGE: $text")
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
                            else -> this@WebSocketClient.onMessage(text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing server message", e)
                        logFileWriter?.println("ERROR PARSING INCOMING MESSAGE: ${e.message}")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    Log.w(TAG, "WebSocket closing: $code - $reason")
                    logFileWriter?.println("WEB_SOCKET_CLOSING: Code=$code, Reason=$reason")
                    cleanup()
                    this@WebSocketClient.onClosing(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    Log.e(TAG, "WebSocket failure", t)
                    logFileWriter?.println("WEB_SOCKET_FAILURE: ${t.message}, Response=${response?.code}")
                    cleanup()
                    this@WebSocketClient.onFailure(t)
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
                val messageToSend = gson.toJson(realtimeInput)
                Log.d(TAG, "Sending audio message (length: ${messageToSend.length})")
                logFileWriter?.println("OUTGOING AUDIO MESSAGE (length: ${messageToSend.length}): ${messageToSend.take(500)}...")
                webSocket?.send(messageToSend)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send audio", e)
                logFileWriter?.println("ERROR SENDING AUDIO: ${e.message}")
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
        logFileWriter?.println("--- Session Log End ---")
        logFileWriter?.close()
        logFileWriter = null
        isConnected = false
        isSetupComplete = false
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
}
