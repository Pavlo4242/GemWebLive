// app/src/main/java/com/gemweblive/WebSocketClient.kt
package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.gemweblive.util.ConfigBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.Executors

class WebSocketClient(
    private val context: Context,
    private val modelInfo: ModelInfo,
    private val configBuilder: ConfigBuilder,
    private val vadSilenceMs: Int,
    private val apiVersion: String,
    private val apiKey: String,
    private val sessionHandle: String?,
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
    private val client = OkHttpClient()
    private val gson = Gson()
    private var logFileWriter: PrintWriter? = null

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val TAG = "WebSocketClient"
    }

    private fun sendConfigMessage() {
        try {
            val configString = configBuilder.buildWebSocketConfig(modelInfo, sessionHandle)
            Log.d(TAG, "Sending config for ${modelInfo.modelName}: $configString")
            webSocket?.send(configString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config", e)
        }
    }

    fun connect() {
        if (isConnected) return
        Log.i(TAG, "Attempting to connect with session handle: ${sessionHandle?.take(10)}...")

        try {
            val logDir = File(context.getExternalFilesDir(null), "websocket_logs")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "session_log_${System.currentTimeMillis()}.txt")
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
                scope.launch { isConnected = true; sendConfigMessage(); onOpen() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { logFileWriter?.println("IN_TEXT: $text"); processIncomingMessage(text) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch { val decoded = bytes.utf8(); logFileWriter?.println("IN_BYTES: $decoded"); processIncomingMessage(decoded) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { cleanup(); onClosing(code, reason) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { Log.e(TAG, "WebSocket Failure", t); cleanup(); onFailure(t) }
            }
        })
    }

    private fun processIncomingMessage(messageText: String) {
        if (messageText.contains("\"setupComplete\"")) {
            if (!isSetupComplete) {
                isSetupComplete = true
                onSetupComplete()
            }
        } else {
            onMessage(messageText)
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (!isReady()) return
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val realtimeInput = mapOf("realtimeInput" to mapOf("audio" to mapOf("data" to base64Audio, "mime_type" to "audio/pcm;rate=16000")))
        webSocket?.send(gson.toJson(realtimeInput))
    }

    fun sendText(text: String) {
        if (!isReady()) return
        val clientContent = mapOf("clientContent" to mapOf("parts" to listOf(mapOf("text" to text))))
        webSocket?.send(gson.toJson(clientContent))
    }

    fun disconnect() {
        scope.launch { cleanup() }
    }

    private fun cleanup() {
        if (isConnected) {
            webSocket?.close(1000, "Normal closure")
        }
        webSocket = null
        isConnected = false
        isSetupComplete = false
        logFileWriter?.close()
        logFileWriter = null
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
}
