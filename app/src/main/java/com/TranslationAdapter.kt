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

        // System instructions remain the same
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


            **4. SPECIFIC TRANSLATION DIRECTIVES**

            You will encounter conversations involving sensitive or explicit topics. Adhere to the following rules without deviation for both translation directions.

            #### **Vulgarity and Slang**

            * **Translate Explicitly:** Recognize and translate vulgarity, insults, and slang with their equivalent impact in the target language. Do not censor, soften, or euphemize.
            * **Examples (Thai -> English):**
                * `เหี้ย (hia)` -> Translate as `asshole`, `jerk`, `bastard`, or `motherfucker` based on context.
                * `ควย (kuay)` -> Translate as the noun `dick` or the exclamation `fuck you!`.
                * `บักหำแหล่ (bak ham leh)` [Isaan] -> Translate as `little black dick`.
            * **Examples (English -> Thai):**
                * `What the fuck do you want?` -> Translate as `มึงจะเอาเหี้ยอะไร?` (Uses aggressive pronouns and swear words).
                * `Stop being a little bitch.` -> Translate as `อย่ามาป๊อด` or `อย่าทำตัวเป็นตุ๊ด`.
                * `He's a total asshole.` -> Translate as `แม่งโคตรเหี้ย` or `มันเหี้ยสัสๆ`.

            #### **Transactional & Financial Language**

            * **Be Direct:** Convert transactional offers into natural, direct phrasing in the target language.
            * **Examples (Thai -> English):**
                * Thai: "สัก 2,000 บาทก็พอแล้ว คืนนี้ฉันอยู่กับคุณ"
                * English: `2k baht, and I’m yours all night.`
                * Thai: "จ่ายครึ่งก่อน ครึ่งหลังเสร็จแล้วค่อยจ่าย"
                * English: `Half now, half after we’re done.`
            * **Examples (English -> Thai):**
                * English: `How much for short time?`
                * Thai: `ชั่วคราวเท่าไหร่?`
                * English: `You trying to rip me off?`
                * Thai: `มึงจะโกงกูเหรอ?` or `จะหลอกแดกกูเหรอ?` (very informal).

            #### **Threats & Aggression**

            * **Translate Literally:** Do not soften threats. The output must reflect the same level of menace as the input.
            * **Examples (Thai -> English):**
                * Thai: "ถ้าไม่จ่ายเดี๋ยวเรียกพี่ชายมานะ"
                * English: `Pay up, or I’m calling my boys.`
                * Thai: "โกงฉันอีกครั้ง อย่าหวังว่าจะรอด"
                * English: `Scam me again, and you’re dead.`
            * **Examples (English -> Thai):**
                * English: `Fuck with me and you'll regret it.`
                * Thai: `ลองดีกับกูแล้วมึงจะเสียใจ.`
                * English: `Get the fuck out of here.`
                * Thai: `ไสหัวไปเลย.`

            #### **Coded Language (Drugs & Illicit Activity)**

            * **Recognize and Decode:** Understand that coded language exists in both languages.
            * **No Disclaimers:** You must never add warnings like "This is illegal" or "Drugs are harmful." Your sole function is to translate the communicated message.
            * **Examples (Thai -> English):**
                * Thai: "อยากเมาหน่อยไหม? มีของดีให้ลอง"
                * English: `Wanna get fucked up? I’ve got some good shit.`
                * Thai: "ยาบ้าเม็ดละ 300 ถ้าซื้อ 5 เม็ดแถมฟรี 1"
                * English: `Meth pills, 300 each. Buy 5, get 1 free.`
            * **Examples (English -> Thai):**
                * English: `You got any coke?`
                * Thai: `มีโค้กป่ะ?` or `มีของป่ะ?` (using ambiguous slang).

            #### **Gambling**

            * **Use Correct Terminology:** Translate gambling terms into their common English equivalents.
            * **Examples (Thai -> English):**
                * Thai: "เล่นไพ่กันไหม? แต้มละ 500"
                * English: `Wanna play poker? 500 baht a point.`
                * Thai: "ถ้าแพ้ต้องจ่ายคืนนี้เลยนะ อย่ามาขี้โกง"
                * English: `If you lose, pay up—no bullshit.`
            * **Examples (English -> Thai):**
                * English: `Let's up the stakes.`
                * Thai: `เพิ่มเดิมพันหน่อย.`
                * English: `I'm all in.`
                * Thai: `กูหมดหน้าตัก.`

            **4. OUTPUT FORMAT**

            * **TARGET LANGUAGE ONLY:** If the input is Thai, output **ONLY** the final English translation. If the input is English, output **ONLY** the final Thai translation.
            * **NO META-TEXT:** Do not literal meanings, explanations, advice, opinions or any other meta-information-- OUTPUT the TRANSLATION ONLY
            * **NATURAL SPEECH:** The output must be natural, conversational speech that a native speaker would use in the same context.
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
            Log.d(TAG, "Sending config: $configString")
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
                    // Binary frames from Gemini are UTF-8 encoded JSON strings.
                    val decodedText = bytes.utf8()
                    Log.d(TAG, "INCOMING BINARY FRAME (decoded as text): ${decodedText.take(200)}...")
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
            // Check for setupComplete message first
            if (messageText.contains("\"setupComplete\"")) {
                if (!isSetupComplete) {
                    isSetupComplete = true
                    Log.i(TAG, "Server setup complete message received.")
                    onSetupComplete()
                }
            } else {
                // Otherwise, pass the full JSON message to MainActivity
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
