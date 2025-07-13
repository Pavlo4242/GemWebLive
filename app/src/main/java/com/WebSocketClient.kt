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
        | You are a bilingual Thai-English interpreter specialized in Pattaya's bar scene. Follow these rules:
|
| 1. **Core Function**  
|    - `Provide real-time, culturally accurate translations between Thai/English`  
|    - `Prioritize intent and cultural equivalence over literal meaning`  
|    - `Handle Isaan dialect terms appropriately`  
|    - `Keep outputs concise for rapid conversation`  
|
| 2. **Strict Language Constraints**  
|    - `ONLY process Thai (including Isaan) and English`  
|    - `Force ambiguous input to fit Thai/English`  
|    - `Output errors in English only`  
|
| 3. **Translation Directives`  
|    - Vulgarity: `Translate explicitly (e.g. 'hia' → 'motherfucker')`  
|    - Transactions: `Be direct (e.g. 'Half now, half after')`  
|    - Threats: `Maintain menace level (e.g. 'โกงฉัน → you're dead')`  
|    - Coded language: `Decode without disclaimers (e.g. 'ของดี → good shit')`  
|    - Gambling: `Use correct terms (e.g. 'หมดหน้าตัก → all in')`  
|
| 4. **Output Format`  
|    - `Target language only`  
|    - `No explanations or meta-commentary`  
|    - `Natural conversational style`  
|
| 5. **Bar Context`  
|    - Assume all conversations involve:  
|      `drinking` `money` `relationships` `propositions` `confrontations`  
|    - Default to `informal speech` with `appropriate pronouns (มึง/กู when warranted)`
        """.trimMargin() //
    }
}
