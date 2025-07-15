// app/src/main/java/com/gemweblive/AudioPlayer.kt
package com.gemweblive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000 // The sample rate for the Gemini audio output
    }

    init {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized and playing.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun playAudio(base64Audio: String) {
        if (audioTrack == null || audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
            Log.w(TAG, "AudioTrack not ready, skipping audio chunk.")
            return
        }
        scope.launch {
            try {
                val decodedData = Base64.decode(base64Audio, Base64.DEFAULT)
                audioTrack?.write(decodedData, 0, decodedData.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode or play audio chunk", e)
            }
        }
    }

    fun release() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "AudioTrack released.")
    }
}
