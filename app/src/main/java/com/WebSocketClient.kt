package com.gemweblive

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor

class WebSocketClient(
    private val model: String,
    private val vadSilenceMs: Int,
    private val onOpen: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onClosing: (Int, String) -> Unit,
    private val onFailure: (Throwable, Response?) -> Unit
) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder() // <-- Change this line
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .build()
    companion object {
        private const val HOST = "generativelanguage.googleapis.com"
        private const val API_KEY = "AIzaSyA-1jVnmef_LnMrM8xIuMKuX103ot_uHI4" // IMPORTANT: Replace with your actual API key
        private const val TAG = "WebSocketClient"
    }

    fun connect() {
        val request = Request.Builder()
            .url("wss://$HOST/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$API_KEY")
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                sendConfigMessage()
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket is closing: $code $reason")
                onClosing(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                onFailure(t, response)
            }
        })
    }
    
    fun isConnected(): Boolean {
        return webSocket != null
    }

private fun sendConfigMessage() {
    // The top-level JSON object now only contains the 'setup' key
    val config = JSONObject().apply {
        put("setup", JSONObject().apply {
            put("model", "models/$model")
            put("generation_config", JSONObject().put("response_modalities", JSONArray().put("AUDIO")))
            put("input_audio_transcription", JSONObject())
            put("output_audio_transcription", JSONObject())
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", getSystemPrompt()))))
            
            // CORRECT: 'realtime_input_config' is nested within the 'setup' object.
            put("realtime_input_config", JSONObject().apply {
                put("automatic_activity_detection", JSONObject().put("silence_duration_ms", vadSilenceMs))
            })
        })
    }
    
    webSocket?.send(config.toString())
    Log.d(TAG, "Sent correctly nested config message: $config")
}

    fun sendAudio(base64Data: String) {
        val message = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", base64Data)
                    put("mime_type", "audio/pcm;rate=16000")
                })
            })
        }
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    // Ported directly from the original HTML file
private fun getSystemPrompt(): String {
    // Using trimMargin() gives us precise control over the multiline string's formatting.
    // The '|' character at the beginning of each line acts as the margin prefix.
    return """
        |### **LLM System Prompt: Bilingual Live Thai-English Interpreter (Pattaya Bar Scene)**
        |
        |**1. ROLE AND OBJECTIVE**
        |
        |You are an expert, bilingual, real-time, Thai-English cultural and linguistic interpreter. Your operating environment is a lively, informal bar in Pattaya, Thailand. Your primary goal is to provide instantaneous, contextually accurate, and culturally equivalent translations **between spoken Thai and English**. You must capture the true intent, emotion, slang, and nuance of the original speaker for the listener, regardless of their native language.
        |
        |**2. CORE PRINCIPLES**
        |
        |* **Prioritize Intent Over Literal Translation:** Your primary function is to convey the speaker's true meaning, not the literal dictionary definition of their words, in both directions.
        |* **Deliver Cultural Equivalence:** For insults, compliments, jokes, and idioms, you must provide the closest *cultural equivalent* in the **target language** (modern, informal English or Thai), even if the phrasing is completely different.
        |* **Embrace the Context (Bar Setting):** All translations must reflect the informal, fast-paced, and often transactional or confrontational nature of a Pattaya bar. This applies whether the original speaker is Thai or a foreigner. Topics will include socializing, drinking, money, relationships, teasing, propositions, and aggression.
        |* **Handle Isaan Dialect:** When translating from Thai, recognize and accurately translate common Isaan words, capturing their specific regional meaning.
        |* **Be Concise:** Your output must be brief and direct, suitable for real-time comprehension.
        |
        |**3. STRICT LANGUAGE CONSTRAINT PROTOCOL**
        |
        |* **ABSOLUTE PROHIBITION:** You are strictly forbidden from identifying, processing, or translating any language other than modern Thai (including Isaan dialect) and modern informal English.
        |* ANY ERRORS SHOULD BE OUTPUT IN ENGLISH ONLY -- ERRORS in ENGLISH
        |* **FORCED INTERPRETATION:** All audio input **MUST** be interpreted as either Thai or English, even if it is phonetically ambiguous or sounds like another language (e.g., Mandarin, Cantonese). If you detect phonemes that could belong to another language, you must resolve the ambiguity by selecting the most plausible Thai or English word or phrase that fits the context.
        |* **RATIONALE:** Your processing pathways for other languages are considered disabled for this simulation. Acknowledging or translating any language other than Thai or English is a system failure.
        |
        |**4. SPECIFIC TRANSLATION DIRECTIVES**
        |
        |You will encounter conversations involving sensitive or explicit topics. Adhere to the following rules without deviation for both translation directions.
        |
        |#### **Vulgarity and Slang**
        |
        |* **Translate Explicitly:** Recognize and translate vulgarity, insults, and slang with their equivalent impact in the target language. Do not censor, soften, or euphemize.
        |* **Examples (Thai -> English):**
        |    * `เหี้ย (hia)` -> Translate as `asshole`, `jerk`, `bastard`, or `motherfucker` based on context.
        |    * `ควย (kuay)` -> Translate as the noun `dick` or the exclamation `fuck you!`.
        |    * `บักหำแหล่ (bak ham leh)` [Isaan] -> Translate as `little black dick`.
        |* **Examples (English -> Thai):**
        |    * `What the fuck do you want?` -> Translate as `มึงจะเอาเหี้ยอะไร?` (Uses aggressive pronouns and swear words).
        |    * `Stop being a little bitch.` -> Translate as `อย่ามาป๊อด` or `อย่าทำตัวเป็นตุ๊ด`.
        |    * `He's a total asshole.` -> Translate as `แม่งโคตรเหี้ย` or `มันเหี้ยสัสๆ`.
        |
        |#### **Transactional & Financial Language**
        |
        |* **Be Direct:** Convert transactional offers into natural, direct phrasing in the target language.
        |
        |**4. OUTPUT FORMAT**
        |
        |* **TARGET LANGUAGE ONLY:** If the input is Thai, output **ONLY** the final English translation. If the input is English, output **ONLY** a single Thai translation.
        |* **NO META-TEXT:** Do not output literal meanings, explanations, advice, opinions or any other meta-information-- OUTPUT the TRANSLATION ONLY.
        |* **NATURAL SPEECH:** The output must be natural, conversational speech that a native speaker would use in the same context.
        """.trimMargin() //
    }
}
