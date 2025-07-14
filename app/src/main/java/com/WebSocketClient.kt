// app/src/main/java/com/gemweblive/WebSocketClient.kt
package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.FieldNamingPolicy
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ByteString // <--- THIS LINE IS ABSOLUTELY ESSENTIAL AND MUST BE HERE
import okhttp3.logging.HttpLoggingInterceptor
import okio.ByteString
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
    private val apiKey: String,
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
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
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
        private const val TAG = "WebSocketClient"

        private val SYSTEM_INSTRUCTION_TEXT = """
            You are a helpful assistant. Translate between English and English.
            Be direct and concise.
        """.trimIndent()
    }

    private fun sendConfigMessage() {
        try {
            val config = mapOf(
                "setup" to mapOf(
                    "model" to "models/$model",
                    "generationConfig" to mapOf(
                        "responseModalities" to listOf("AUDIO")
                    ),
                    "inputAudioTranscription" to emptyMap<String, Any>(),
                    "outputAudioTranscription" to emptyMap<String, Any>(),
                    "systemInstruction" to mapOf(
                        "parts" to listOf(
                            mapOf("text" to SYSTEM_INSTRUCTION_TEXT)
                        )
                    ),
                    "realtimeInputConfig" to mapOf(
                        "automaticActivityDetection" to mapOf(
                            "silenceDurationMs" to vadSilenceMs
                        )
                    )
                )
            )

            val configString = gson.toJson(config)
            Log.d(TAG, "Sending config (length: ${configString.length}): $configString")
            logFileWriter?.println("OUTGOING CONFIG FRAME: $configString")
            webSocket?.send(configString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config", e)
        }
    }

    fun connect() {
        if (isConnected) return
        Log.i(TAG, "Attempting to connect...")

        try {
            val logDir = File(context.getExternalFilesDir(null), "websocket_logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "session_log_${System.currentTimeMillis()}.txt")
            logFileWriter = PrintWriter(FileWriter(logFile, true), true)
            logFileWriter?.println("--- New WebSocket Session Log: ${java.util.Date()} ---")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
            onFailure(e)
            return
        }

        val request = Request.Builder()
            .url("wss://$HOST/ws/google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    Log.i(TAG, "WebSocket connection opened. Response: ${response.code}")
                    logFileWriter?.println("WEB_SOCKET_OPENED (HTTP Status: ${response.code})")
                    logFileWriter?.println("--- HTTP Response Headers ---")
                    response.headers.forEach { header ->
                        logFileWriter?.println("${header.first}: ${header.second}")
                    }
                    logFileWriter?.println("---------------------------")
                    isConnected = true
                    sendConfigMessage()
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    Log.d(TAG, "INCOMING TEXT FRAME: ${text.take(500)}...")
                    logFileWriter?.println("INCOMING TEXT FRAME: $text")
                    try {
                        val responseMap = gson.fromJson(text, Map::class.java)
                        when {
                            responseMap?.containsKey("setupComplete") == true -> {
                                if (!isSetupComplete) {
                                    isSetupComplete = true
                                    Log.i(TAG, "Server setup complete message received.")
                                    onSetupComplete()
                                }
                            }
                            else -> this@WebSocketClient.onMessage(text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing incoming TEXT frame", e)
                        logFileWriter?.println("ERROR PARSING INCOMING TEXT FRAME: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { // <--- ByteString used here
                scope.launch {
                    val base64Encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                    Log.d(TAG, "INCOMING BINARY FRAME (length: ${bytes.size()}): ${base64Encoded.take(100)}...")
                    logFileWriter?.println("INCOMING BINARY FRAME (length: ${bytes.size()}): $base64Encoded")
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
                    logFileWriter?.println("WEB_SOCKET_FAILURE: ${t.message}, Response=${response?.code}, ResponseBody=${response?.body?.string()}")
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
                val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
                val realtimeInput = mapOf(
                    "realtimeInput" to mapOf(
                        "audio" to mapOf(
                            "data" to base64Audio,
                            "mime_type" to "audio/pcm;rate=16000"
                        )
                    )
                )
                val messageToSend = gson.toJson(realtimeInput)
                Log.d(TAG, "OUTGOING AUDIO FRAME (length: ${messageToSend.length}): ${messageToSend.take(500)}...")
                logFileWriter?.println("OUTGOING AUDIO FRAME: $messageToSend")
                webSocket?.send(messageToSend)
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
        logFileWriter?.println("--- Session Log End ---")
        logFileWriter?.close()
        logFileWriter = null
        isConnected = false
        isSetupComplete = false
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
    fun isConnected(): Boolean = isConnected
}
