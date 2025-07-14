// WebSocketClient.java
package com.gemweblive;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.logging.HttpLoggingInterceptor;

public class WebSocketClient {

    private static final String TAG = "WebSocketClient";
    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String API_KEY = "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8"; // Use your actual API key
    private static final String SYSTEM_INSTRUCTION_TEXT = "You are a helpful assistant. Translate between English and Thai.\nBe direct and concise.";

    private final Context context;
    private final String model;
    private final int vadSilenceMs;
    private final String apiVersion;
    private final Runnable onOpen;
    private final Consumer<String> onMessage;
    private final BiConsumer<Integer, String> onClosing;
    private final Consumer<Throwable> onFailure;
    private final Runnable onSetupComplete;

    private WebSocket webSocket;
    private volatile boolean isSetupComplete = false;
    private volatile boolean isConnected = false;

    private final ExecutorService scope = Executors.newSingleThreadExecutor();
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private PrintWriter logFileWriter;
    private final OkHttpClient client;

    public WebSocketClient(Context context, String model, int vadSilenceMs, String apiVersion,
                           Runnable onOpen, Consumer<String> onMessage, BiConsumer<Integer, String> onClosing,
                           Consumer<Throwable> onFailure, Runnable onSetupComplete) {
        this.context = context;
        this.model = model;
        this.vadSilenceMs = vadSilenceMs;
        this.apiVersion = apiVersion;
        this.onOpen = onOpen;
        this.onMessage = onMessage;
        this.onClosing = onClosing;
        this.onFailure = onFailure;
        this.onSetupComplete = onSetupComplete;

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(message -> {
            Log.d(TAG, message);
            if (logFileWriter != null) {
                logFileWriter.println(message);
            }
        });
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        this.client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build();
    }

    public void connect() {
        if (isConnected) return;
        Log.i(TAG, "Attempting to connect...");

        try {
            File logDir = new File(context.getExternalFilesDir(null), "http_logs");
            if (!logDir.exists()) logDir.mkdirs();
            File logFile = new File(logDir, "network_log_" + System.currentTimeMillis() + ".txt");
            logFileWriter = new PrintWriter(new FileWriter(logFile, true), true);
            logFileWriter.println("--- New Session Log: " + new java.util.Date() + " ---");
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize log file", e);
            onFailure.accept(e);
            return;
        }

        String url = "wss://" + HOST + "/ws/google.ai.generativelanguage." + apiVersion + ".GenerativeService.BidiGenerateContent?key=" + API_KEY;
        Request request = new Request.Builder().url(url).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                scope.execute(() -> {
                    Log.i(TAG, "WebSocket connection opened");
                    logFileWriter.println("WEB_SOCKET_OPENED");
                    isConnected = true;
                    sendConfigMessage();
                    onOpen.run();
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                scope.execute(() -> {
                    Log.d(TAG, "Server message: " + text.substring(0, Math.min(text.length(), 500)) + "...");
                    logFileWriter.println("INCOMING MESSAGE: " + text);
                    try {
                        Map<?, ?> responseMap = gson.fromJson(text, Map.class);
                        if (responseMap.containsKey("setupComplete")) {
                            if (!isSetupComplete) {
                                isSetupComplete = true;
                                Log.i(TAG, "Server setup complete");
                                onSetupComplete.run();
                            }
                        } else {
                            WebSocketClient.this.onMessage.accept(text);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing server message", e);
                        logFileWriter.println("ERROR PARSING INCOMING MESSAGE: " + e.getMessage());
                    }
                });
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                scope.execute(() -> {
                    Log.w(TAG, "WebSocket closing: " + code + " - " + reason);
                    logFileWriter.println("WEB_SOCKET_CLOSING: Code=" + code + ", Reason=" + reason);
                    cleanup();
                    WebSocketClient.this.onClosing.accept(code, reason);
                });
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                scope.execute(() -> {
                    Log.e(TAG, "WebSocket failure", t);
                    logFileWriter.println("WEB_SOCKET_FAILURE: " + t.getMessage() + ", Response=" + (response != null ? response.code() : "null"));
                    cleanup();
                    WebSocketClient.this.onFailure.accept(t);
                });
            }
        });
    }

    private void sendConfigMessage() {
        try {
            SetupPayload payload = new SetupPayload(
                new Setup(
                    "models/" + model,
                    new GenerationConfig(),
                    new TranscriptionConfig(),
                    new TranscriptionConfig(),
                    new SystemInstruction(Collections.singletonList(new Part(SYSTEM_INSTRUCTION_TEXT))),
                    new RealtimeInputConfig(new AutomaticActivityDetection(vadSilenceMs))
                )
            );
            String configString = gson.toJson(payload);
            Log.d(TAG, "Sending config (length: " + configString.length() + "): " + configString);
            logFileWriter.println("OUTGOING CONFIG: " + configString);
            webSocket.send(configString);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send config", e);
        }
    }
    
    public void sendAudio(byte[] audioData) {
        if (!isReady()) return;
        scope.execute(() -> {
            try {
                AudioPayload payload = new AudioPayload(
                    new RealtimeInput(
                        new Audio(Base64.encodeToString(audioData, Base64.NO_WRAP))
                    )
                );
                String messageToSend = gson.toJson(payload);
                Log.d(TAG, "Sending audio message (length: " + messageToSend.length() + ")");
                logFileWriter.println("OUTGOING AUDIO MESSAGE (length: " + messageToSend.length() + "): " + messageToSend.substring(0, Math.min(messageToSend.length(), 500)) + "...");
                webSocket.send(messageToSend);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send audio", e);
                logFileWriter.println("ERROR SENDING AUDIO: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        scope.execute(this::cleanup);
    }

    private void cleanup() {
        if (isConnected) {
            Log.i(TAG, "Cleaning up WebSocket connection");
            if (webSocket != null) {
                webSocket.close(1000, "Normal closure");
                webSocket = null;
            }
        }
        if (logFileWriter != null) {
            logFileWriter.println("--- Session Log End ---");
            logFileWriter.close();
            logFileWriter = null;
        }
        isConnected = false;
        isSetupComplete = false;
    }

    public boolean isReady() {
        return isConnected && isSetupComplete;
    }

    // --- POJO Classes for GSON Serialization ---
    static class SetupPayload { Setup setup; public SetupPayload(Setup s) { this.setup = s; } }
    static class Setup {
        String model;
        GenerationConfig generationConfig;
        TranscriptionConfig inputAudioTranscription;
        TranscriptionConfig outputAudioTranscription;
        SystemInstruction systemInstruction;
        RealtimeInputConfig realtimeInputConfig;
        public Setup(String m, GenerationConfig gc, TranscriptionConfig iat, TranscriptionConfig oat, SystemInstruction si, RealtimeInputConfig ric) {
            this.model = m; this.generationConfig = gc; this.inputAudioTranscription = iat; this.outputAudioTranscription = oat; this.systemInstruction = si; this.realtimeInputConfig = ric;
        }
    }
    static class GenerationConfig { List<String> responseModalities = Collections.singletonList("AUDIO"); }
    static class TranscriptionConfig {} // Empty object
    static class SystemInstruction { List<Part> parts; public SystemInstruction(List<Part> p) { this.parts = p; } }
    static class Part { String text; public Part(String t) { this.text = t; } }
    static class RealtimeInputConfig { AutomaticActivityDetection automaticActivityDetection; public RealtimeInputConfig(AutomaticActivityDetection aad) { this.automaticActivityDetection = aad; } }
    static class AutomaticActivityDetection { int silenceDurationMs; public AutomaticActivityDetection(int s) { this.silenceDurationMs = s; } }

    static class AudioPayload { RealtimeInput realtimeInput; public AudioPayload(RealtimeInput r) { this.realtimeInput = r; } }
    static class RealtimeInput { Audio audio; public RealtimeInput(Audio a) { this.audio = a; } }
    static class Audio { String data; @SerializedName("mime_type") String mimeType = "audio/pcm;rate=16000"; public Audio(String d) { this.data = d; } }
}
