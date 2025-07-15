package com.gemweblive
package com.orbitalsonic.speechrecognition

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.orbitalsonic.speechrecognition.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(),onRecognitionListener {

    private lateinit var binding: ActivityMainBinding

    private lateinit var speechToTextConverter: SpeechToTextConverter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechToTextConverter = SpeechToTextConverter(this,this)

        // Request microphone permission if not granted already
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                1
            )
        }
        binding.btnSpeak.setOnClickListener {
            // Start listening
//            speechToTextConverter.startListening("en-US")
//            speechToTextConverter.startListening("es-ES")
//            speechToTextConverter.startListening("hi")
//            speechToTextConverter.startListening("ar")
//            speechToTextConverter.startListening("ur")
            speechToTextConverter.startListening("en")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop listening
        speechToTextConverter.stopListening()
    }

    override fun onReadyForSpeech() {
        binding.btnSpeak.visibility = View.GONE
        binding.tvResult.visibility = View.GONE
        binding.tvMessage.visibility = View.VISIBLE
        binding.lavMicAnimation.visibility = View.VISIBLE
    }
    override fun onBeginningOfSpeech() {}
    override fun onEndOfSpeech() {}
    override fun onError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        binding.btnSpeak.visibility = View.VISIBLE
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = "Sorry, Please try again"
        binding.tvMessage.visibility = View.GONE
        binding.lavMicAnimation.visibility = View.GONE
    }

    override fun onResults(results: String) {
        binding.btnSpeak.visibility = View.VISIBLE
        binding.tvResult.visibility = View.VISIBLE
        binding.tvMessage.visibility = View.GONE
        binding.lavMicAnimation.visibility = View.GONE
        binding.tvResult.text = results
    }


}


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat


class SpeechToTextConverter(private val context: Context,private val onRecognitionListener: onRecognitionListener) {
    private val TAG_RECOGNITION = "SpeechRecognitionTag"
    private val speechRecognizer: SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context)

    private val recognitionListener: RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onRecognitionListener.onReadyForSpeech()
            Log.d(TAG_RECOGNITION, "onReadyForSpeech:")
        }
        override fun onBeginningOfSpeech() {
            onRecognitionListener.onBeginningOfSpeech()
            Log.d(TAG_RECOGNITION, "onBeginningOfSpeech:")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            onRecognitionListener.onEndOfSpeech()
            Log.d(TAG_RECOGNITION, "onEndOfSpeech:")
        }
        override fun onError(error: Int) {
            onRecognitionListener.onError("Something happened, onError()")
            Log.e(TAG_RECOGNITION, "Something happened, onError()")
        }
        override fun onResults(results: Bundle?) {
            val resultArray = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!resultArray.isNullOrEmpty()) {
                try {
                    val spokenText = resultArray[0]
                    onRecognitionListener.onResults(spokenText)
                }catch (ex:Exception){
                    onRecognitionListener.onError("${ex.message}")
                    Log.e(TAG_RECOGNITION, "${ex.message}")
                }
            }else{
                onRecognitionListener.onError("List is Empty or Null")
                Log.e(TAG_RECOGNITION, "List is Empty or Null")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening(language: String) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onRecognitionListener.onError("Microphone permission not granted")
            Log.e(TAG_RECOGNITION, "Microphone permission not granted")
            return
        }

        try {
            val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            speechRecognizer.setRecognitionListener(recognitionListener)
            speechRecognizer.startListening(speechRecognizerIntent)
        }catch (ex:Exception){
            onRecognitionListener.onError("${ex.message}")
            Log.e(TAG_RECOGNITION, "${ex.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer.stopListening()
        }catch (ex:Exception){
            onRecognitionListener.onError("${ex.message}")
            Log.e(TAG_RECOGNITION, "${ex.message}")
        }

    }
}
