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
    private val context: Context, // Added context to constructor
    private val model: String,
    private val vadSilenceMs: Int,
    private val apiVersion: String, // Added apiVersion to constructor
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
        .pingInterval(30, TimeUnit.SECONDS) // Keep connection alive
        .addInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                // This logger captures HTTP requests/responses for the initial handshake.
                // It does NOT capture WebSocket frames (messages) after connection is established.
                Log.d(TAG, message) // Log to Logcat
                logFileWriter?.println(message) // Log to file
            }
        }).apply {
            level = HttpLoggingInterceptor.Level.BODY // Change to BODY for full request/response
        })
        .build()

    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val API_KEY = "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8" // Replace with your actual API key
        private const val TAG = "WebSocketClient"

        // Extracted the large system instruction text into a constant
        // Removed 'const' as multi-line strings with trimIndent() are not compile-time constants
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
            * **Examples (English -> Thai)::**
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
            * **Examples (English -> Thai)::**
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
        """.trimIndent() // Use trimIndent() for consistency with triple-quoted strings
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
                            mapOf("text" to SYSTEM_INSTRUCTION_TEXT) // Use the constant here
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
            Log.d(TAG, "Sending config (length: ${configString.length}): $configString") // Added length logging
            logFileWriter?.println("OUTGOING CONFIG: $configString") // Log config message to file
            webSocket?.send(configString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config", e)
        }
    }

    fun connect() {
        if (isConnected) return
        Log.i(TAG, "Attempting to connect...")

        try {
            // Initialize log file in external files directory
            val logDir = File(context.getExternalFilesDir(null), "http_logs")
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
            .url("wss://$HOST/ws/google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent?key=$API_KEY") // Changed URL to use apiVersion
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    Log.i(TAG, "WebSocket connection opened")
                    logFileWriter?.println("WEB_SOCKET_OPENED") // Log WebSocket open event
                    isConnected = true
                    sendConfigMessage()
                    onOpen()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    Log.d(TAG, "Server message: ${text.take(500)}...")
                    logFileWriter?.println("INCOMING MESSAGE: $text") // Log all incoming messages to file
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
                            else -> this@WebSocketClient.onMessage(text) // Corrected callback invocation
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing server message", e)
                        logFileWriter?.println("ERROR PARSING INCOMING MESSAGE: ${e.message}") // Log parsing errors
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    Log.w(TAG, "WebSocket closing: $code - $reason")
                    logFileWriter?.println("WEB_SOCKET_CLOSING: Code=$code, Reason=$reason") // Log closing event
                    cleanup()
                    this@WebSocketClient.onClosing(code, reason) // Corrected callback invocation
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    Log.e(TAG, "WebSocket failure", t)
                    logFileWriter?.println("WEB_SOCKET_FAILURE: ${t.message}, Response=${response?.code}") // Log failure event
                    cleanup()
                    this@WebSocketClient.onFailure(t) // Corrected callback invocation
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
                Log.d(TAG, "Sending audio message (length: ${messageToSend.length})") // Log audio message length
                logFileWriter?.println("OUTGOING AUDIO MESSAGE (length: ${messageToSend.length}): ${messageToSend.take(500)}...") // Log outgoing audio message to file (truncated for brevity in log, but full in file)
                webSocket?.send(messageToSend)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send audio", e)
                logFileWriter?.println("ERROR SENDING AUDIO: ${e.message}") // Log audio sending errors
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
        logFileWriter?.println("--- Session Log End ---") // Mark end of session in log file
        logFileWriter?.close() // Close the log file writer
        logFileWriter = null
        isConnected = false
        isSetupComplete = false
    }

    fun isReady(): Boolean = isConnected && isSetupComplete
}
