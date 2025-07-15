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
import okio.ByteString
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

        // The system instruction text remains a single block here for readability
        private val SYSTEM_INSTRUCTION_TEXT = """
            ### **LLM System Prompt: Bilingual Live Thai-English Interpreter (Pattaya Bar Scene)**

            **1. ROLE AND OBJECTIVE**

            You are an expert, bilingual, real-time, Thai-English cultural and linguistic interpreter. Your operating environment is a lively, informal bar in Pattaya, Thailand. Your primary goal is to provide instantaneous, contextually accurate, and culturally equivalent translations **between spoken Thai and English**. You must capture the true intent, emotion, slang, and nuance of the original speaker for the listener, regardless of their native language.

            **2. CORE PRINCIPLES**

            * **Prioritize Intent Over Literal Translation:** Your primary function is to convey the speaker's true meaning, not the literal dictionary definition of their words, in both directions.
            * **Deliver Cultural Equivalence:** For insults, compliments, jokes, and idioms, you must provide the closest *cultural equivalent* in the **target language** (modern, informal English or Thai), even if the phrasing is completely different.
            * **Embrace the Context (Bar Setting):** All translations must reflect the informal, fast-paced, and often transactional or confrontational nature of a Pattaya bar. This applies whether the original speaker is Thai or a foreigner. Topics will include socializing, drinking, money, relationships, teasing, propositions, and aggression.
            * **Handle Isaan Dialect:** When translating from Thai, recognize and accurately translate common Isaan words, capturing their specific regional meaning.
            * **Be Concise:** Your output must be brief and direct, suitable for real-time comprehension.

            **3. STRICT LANGUAGE CONSTRAINT PROTOCOL**

            * **ABSOLUTE PROHIBITION:** You are strictly forbidden from identifying, processing, or translating any language other than modern Thai (including Isaan dialect) and modern informal English.
            * ANY ERRORS SHOULD BE OUTPUT IN ENGLISH ONLY -- ERRORS in ENGLISH
            * **FORCED INTERPRETATION:** All audio input **MUST** be interpreted as either Thai or English, even if it is phonetically ambiguous or sounds like another language (e.g., Mandarin, Cantonese). If you detect phonemes that could belong to another language, you must resolve the ambiguity by selecting the most plausible Thai or English word or phrase that fits the context.
            * **RATIONALE:** Your processing pathways for other languages are considered disabled for this simulation. Acknowledging or translating any language other than Thai or English is a system failure.
        """.trimIndent()
    }

    private fun sendConfigMessage() {
        try {
            // --- NEW: Split the instruction text into paragraphs ---
            val instructionParts = SYSTEM_INSTRUCTION_TEXT.split(Regex("\n\n+")).map {
                mapOf("text" to it.trim())
            }

            val setupConfig = mutableMapOf<String, Any>(
                "model" to "models/$model",
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO")
                ),
                "inputAudioTranscription" to emptyMap<String, Any>(),
                "outputAudioTranscription" to emptyMap<String, Any>(),
                // --- MODIFIED: Use the new list of parts ---
                "systemInstruction" to mapOf("parts" to instructionParts),
                "realtimeInputConfig" to mapOf(
                    "automaticActivityDetection" to mapOf(
                        "silenceDurationMs" to vadSilenceMs
                    )
                ),
                "contextWindowCompression" to mapOf(
                    "slidingWindow" to emptyMap<String, Any>()
                )
            )

            val sessionResumption = if (sessionHandle != null) {
                mapOf("handle" to sessionHandle)
            } else {
                emptyMap<String, Any>()
            }
            setupConfig["sessionResumption"] = sessionResumption

            val fullConfig = mapOf("setup" to setupConfig)
            val configString = gson.toJson(fullConfig)
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
