
// app/src/main/java/com/gemweblive/WebSocketClient.kt
package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.gemweblive.ApiModels.ModelInfo
import com.gemweblive.util.ConfigBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
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

    private fun sendConfigMessage() {
        val configString = configBuilder.buildWebSocketConfig(modelInfo, sessionHandle)
        Log.d("WebSocketClient", "Sending config: $configString")
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
                    isConnected = true
                    logFileWriter?.println("WEB_SOCKET_OPENED (HTTP Status: ${response.code})")
                    sendConfigMessage()
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    logFileWriter?.println("INCOMING TEXT FRAME: $text")
                    processIncomingMessage(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    val decodedText = bytes.utf8()
                    Log.d(TAG, "INCOMING BINARY FRAME (decoded): ${decodedText.take(200)}...")
                    logFileWriter?.println("INCOMING BINARY FRAME (decoded): $decodedText")
                    processIncomingMessage(decodedText)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    logFileWriter?.println("WEB_SOCKET_CLOSING: Code=$code, Reason=$reason")
                    cleanup()
                    this@WebSocketClient.onClosing(code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    Log.e(TAG, "WebSocket failure", t)
                    logFileWriter?.println("WEB_SOCKET_FAILURE: ${t.message}")
                    cleanup()
                    this@WebSocketClient.onFailure(t)
                }
            }
        })
    }
    
    private fun processIncomingMessage(messageText: String) {
        try {
            if (messageText.contains("\"setupComplete\"")) {
                if (!isSetupComplete) {
                    isSetupComplete = true
                    Log.i(TAG, "Server setup complete.")
                    onSetupComplete()
                }
            } else {
                this@WebSocketClient.onMessage(messageText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming message: '$messageText'", e)
        }
    }

    fun sendText(text: String) {
    if (!isReady()) return
    scope.launch {
        try {
            // This structure is based on the BidiGenerateContentClientContent message type
            val clientContentMessage = mapOf(
                "clientContent" to mapOf(
                    "turn" to mapOf(
                         "parts" to listOf(
                            mapOf("text" to text)
                        )
                    ),
                    "turnComplete" to true
                )
            )

            val messageToSend = gson.toJson(clientContentMessage)
            Log.d(TAG, "OUTGOING TEXT FRAME: $messageToSend")
            webSocket?.send(messageToSend)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }
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
            webSocket?.close(1000, "Normal closure")
            webSocket = null
        }
        logFileWriter?.close()
        logFileWriter = null
        isConnected = false
        isSetupComplete = false
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
    fun isConnected(): Boolean = isConnected
}
