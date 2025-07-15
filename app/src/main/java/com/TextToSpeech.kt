// Create a new file, e.g., in a new 'output' package: output/TextToSpeechManager.kt
package com.gemweblive.output

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TextToSpeechManager(context: Context, private val onReady: () -> Unit) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false
    private var desiredLocale: Locale = Locale.US // Default to US English

    companion object {
        private const val TAG = "TextToSpeechManager"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            setLanguage(desiredLocale) // Apply the default or previously set locale
            onReady() // Notify MainActivity that the TTS engine is ready
            Log.i(TAG, "TextToSpeech engine initialized successfully.")
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech engine. Status: $status")
        }
    }

    fun setLanguage(locale: Locale): Boolean {
        if (!isReady) return false
        
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language '$locale' is not supported or missing data.")
            // Here you could prompt the user to install the language pack
            return false
        } else {
            desiredLocale = locale
            Log.i(TAG, "TextToSpeech language set to '$locale'.")
            return true
        }
    }

    fun speak(text: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, cannot speak text.")
            return
        }
        // Use QUEUE_ADD to speak multiple phrases in order, or QUEUE_FLUSH to interrupt.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    fun shutdown() {
        if (isReady) {
            tts.stop()
            tts.shutdown()
        }
    }
}
