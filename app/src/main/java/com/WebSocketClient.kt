// app/src/main/java/com/gemweblive/WebSocketClient.kt
package com.gemweblive

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import java.util.concurrent.Executors

class WebSocketClient(
    private val context: Context,
    private val modelName: String,
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
    private val gson = Gson()
    private val client = OkHttpClient()

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val TAG = "WebSocketClient"
        private val SYSTEM_INSTRUCTION_TEXT = "### **LLM System Prompt..." // Your full prompt text
    }

    private fun sendConfigMessage() {
        val instructionParts = SYSTEM_INSTRUCTION_TEXT.split(Regex("\n\n+")).map {
            mapOf("text" to it.trim())
        }
        val setupConfig = mutableMapOf<String, Any>(
            "model" to "models/$modelName",
            "systemInstruction" to mapOf("parts" to instructionParts),
            "inputAudioTranscription" to emptyMap<String, Any>(),
            "outputAudioTranscription" to emptyMap<String, Any>(),
            "contextWindowCompression" to mapOf("slidingWindow" to emptyMap<String, Any>()),
            "realtimeInputConfig" to mapOf(
                "automaticActivityDetection" to mapOf("silenceDurationMs" to vadSilenceMs)
            )
        )
        sessionHandle?.let {
            setupConfig["sessionResumption"] = mapOf("handle" to it)
        }
        val fullConfig = mapOf("setup" to setupConfig)
        webSocket?.send(gson.toJson(fullConfig))
    }
    
    fun connect() {
        if (isConnected) return
        val request = Request.Builder()
            .url("wss://$HOST/ws/google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { isConnected = true; onOpen(); sendConfigMessage() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { processIncomingMessage(text) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch { processIncomingMessage(bytes.utf8()) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { cleanup(); onClosing(code, reason) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { cleanup(); onFailure(t) }
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
        // ... (This function remains the same)
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
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
}
