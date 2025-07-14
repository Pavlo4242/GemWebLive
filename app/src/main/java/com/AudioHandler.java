// AudioHandler.java
package com.gemweblive;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioHandler {

    // Functional interface for the audio chunk callback
    public interface AudioChunkListener {
        void onAudioChunk(byte[] audioData);
    }

    private static final String TAG = "AudioHandler";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final AudioChunkListener onAudioChunk;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;

    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl agc;
    private AcousticEchoCanceler aec;

    public AudioHandler(Context context, AudioChunkListener onAudioChunk) {
        this.context = context;
        this.onAudioChunk = onAudioChunk;
    }

    public void startRecording() {
        if (isRecording) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord parameters.");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.");
            return;
        }

        audioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();

        int sessionId = audioRecord.getAudioSessionId();
        if (sessionId != 0) {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                noiseSuppressor.setEnabled(true);
                Log.d(TAG, "NoiseSuppressor enabled.");
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                agc.setEnabled(true);
                Log.d(TAG, "AutomaticGainControl enabled.");
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                aec.setEnabled(true);
                Log.d(TAG, "AcousticEchoCanceler enabled.");
            }
        }

        audioRecord.startRecording();
        isRecording = true;
        Log.d(TAG, "Recording started.");

        audioExecutor.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] audioBuffer = new byte[bufferSize];
            while (isRecording) {
                if (audioRecord == null) break;
                int readResult = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (readResult > 0) {
                    byte[] chunk = new byte[readResult];
                    System.arraycopy(audioBuffer, 0, chunk, 0, readResult);
                    onAudioChunk.onAudioChunk(chunk);
                }
            }
        });
    }

    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (noiseSuppressor != null) noiseSuppressor.release();
        if (agc != null) agc.release();
        if (aec != null) aec.release();

        Log.d(TAG, "Recording stopped and resources released.");
    }
}
