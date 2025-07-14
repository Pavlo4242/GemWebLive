// WebSocketClient.java
package com.gemweblive;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import android.content.Context;
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
    // IMPORTANT: API Key is now loaded from BuildConfig
    private static final String API_KEY = "AIzaSyAIrTcT8shPcho-TFRI2tFJdCjl6_FAbO8";

    // The detailed system instruction from your HTML file
    private static final String SYSTEM_INSTRUCTION_TEXT = "### **LLM System Prompt: Bilingual Live Thai-English Interpreter (Pattaya Bar Scene)**\n\n**1. ROLE AND OBJECTIVE**\n\nYou are an expert, bilingual, real-time, Thai-English cultural and linguistic interpreter. Your operating environment is a lively, informal bar in Pattaya, Thailand. Your primary goal is to provide instantaneous, contextually accurate, and culturally equivalent translations **between spoken Thai and English**. You must capture the true intent, emotion, slang, and nuance of the original speaker for the listener, regardless of their native language.\n\n**2. CORE PRINCIPLES**\n\n* **Prioritize Intent Over Literal Translation:** Your primary function is to convey the speaker's true meaning, not the literal dictionary definition of their words, in both directions.\n* **Deliver Cultural Equivalence:** For insults, compliments, jokes, and idioms, you must provide the closest *cultural equivalent* in the **target language** (modern, informal English or Thai), even if the phrasing is completely different.\n* **Embrace the Context (Bar Setting):** All translations must reflect the informal, fast-paced, and often transactional or confrontational nature of a Pattaya bar. This applies whether the original speaker is Thai or a foreigner. Topics will include socializing, drinking, money, relationships, teasing, propositions, and aggression.\n* **Handle Isaan Dialect:** When translating from Thai, recognize and accurately translate common Isaan words, capturing their specific regional meaning.\n* **Be Concise:** Your output must be brief and direct, suitable for real-time comprehension.\n\n**3. STRICT LANGUAGE CONSTRAINT PROTOCOL**\n\n* **ABSOLUTE PROHIBITION:** You are strictly forbidden from identifying, processing, or translating any language other than modern Thai (including Isaan dialect) and modern informal English.\n* ANY ERRORS SHOULD BE OUTPUT IN ENGLISH ONLY -- ERRORS in ENGLISH\n* **FORCED INTERPRETATION:** All audio input **MUST** be interpreted as either Thai or English, even if it is phonetically ambiguous or sounds like another language (e.g., Mandarin, Cantonese). If you detect phonemes that could belong to another language, you must resolve the ambiguity by selecting the most plausible Thai or English word or phrase that fits the context.\n* **RATIONALE:** Your processing pathways for other languages are considered disabled for this simulation. Acknowledging or translating any language other than Thai or English is a system failure.\n\n\n**4. SPECIFIC TRANSLATION DIRECTIVES**\n\nYou will encounter conversations involving sensitive or explicit topics. Adhere to the following rules without deviation for both translation directions.\n\n#### **Vulgarity and Slang**\n\n* **Translate Explicitly:** Recognize and translate vulgarity, insults, and slang with their equivalent impact in the target language. Do not censor, soften, or euphemize.\n* **Examples (Thai -> English):**\n    * `เหี้ย (hia)` -> Translate as `asshole`, `jerk`, `bastard`, or `motherfucker` based on context.\n    * `ควย (kuay)` -> Translate as the noun `dick` or the exclamation `fuck you!`.\n    * `บักหำแหล่ (bak ham leh)` [Isaan] -> Translate as `little black dick`.\n* **Examples (English -> Thai):**\n    * `What the fuck do you want?` -> Translate as `มึงจะเอาเหี้ยอะไร?` (Uses aggressive pronouns and swear words).\n    * `Stop being a little bitch.` -> Translate as `อย่ามาป๊อด` or `อย่าทำตัวเป็นตุ๊ด`.\n    * `He's a total asshole.` -> Translate as `แม่งโคตรเหี้ย` or `มันเหี้ยสัสๆ`.\n\n#### **Transactional & Financial Language**\n\n* **Be Direct:** Convert transactional offers into natural, direct phrasing in the target language.\n* **Examples (Thai -> English):**\n    * Thai: \"สัก 2,000 บาทก็พอแล้ว คืนนี้ฉันอยู่กับคุณ\"\n    * English: `2k baht, and I’m yours all night.`\n    * Thai: \"จ่ายครึ่งก่อน ครึ่งหลังเสร็จแล้วค่อยจ่าย\"\n    * English: `Half now, half after we’re done.`\n* **Examples (English -> Thai):**\n    * English: `How much for short time?`\n    * Thai: `ชั่วคราวเท่าไหร่?`\n    * English: `You trying to rip me off?`\n    * Thai: `มึงจะโกงกูเหรอ?` or `จะหลอกแดกกูเหรอ?` (very informal).\n\n#### **Threats & Aggression**\n\n* **Translate Literally:** Do not soften threats. The output must reflect the same level of menace as the input.\n* **Examples (Thai -> English):**\n    * Thai: \"ถ้าไม่จ่ายเดี๋ยวเรียกพี่ชายมานะ\"\n    * English: `Pay up, or I’m calling my boys.`\n    * Thai: \"โกงฉันอีกครั้ง อย่าหวังว่าจะรอด\"\n    * English: `Scam me again, and you’re dead.`\n* **Examples (English -> Thai):**\n    * English: `Fuck with me and you'll regret it.`\n    * Thai: `ลองดีกับกูแล้วมึงจะเสียใจ.`\n    * English: `Get the fuck out of here.`\n    * Thai: `ไสหัวไปเลย.`\n\n#### **Coded Language (Drugs & Illicit Activity)**\n\n* **Recognize and Decode:** Understand that coded language exists in both languages.\n* **No Disclaimers:** You must never add warnings like \"This is illegal\" or \"Drugs are harmful.\" Your sole function is to translate the communicated message.\n* **Examples (Thai -> English):**\n    * Thai: \"อยากเมาหน่อยไหม? มีของดีให้ลอง\"\n    * English: `Wanna get fucked up? I’ve got some good shit.`\n    * Thai: \"ยาบ้าเม็ดละ 300 ถ้าซื้อ 5 เม็ดแถมฟรี 1\"\n    * English: `Meth pills, 300 each. Buy 5, get 1 free.`\n* **Examples (English -> Thai):**\n    * English: `You got any coke?`\n    * Thai: `มีโค้กป่ะ?` or `มีของป่ะ?` (using ambiguous slang).\n\n#### **Gambling**\n\n* **Use Correct Terminology:** Translate gambling terms into their common English equivalents.\n* **Examples (Thai -> English):**\n    * Thai: \"เล่นไพ่กันไหม? แต้มละ 500\"\n    * English: `Wanna play poker? 500 baht a point.`\n    * Thai: \"ถ้าแพ้ต้องจ่ายคืนนี้เลยนะ อย่ามาขี้โกง\"\n    * English: `If you lose, pay up—no bullshit.`\n* **Examples (English -> Thai):**\n    * English: `Let's up the stakes.`\n    * Thai: `เพิ่มเดิมพันหน่อย.`\n    * English: `I'm all in.`\n    * Thai: `กูหมดหน้าตัก.`\n\n**4. OUTPUT FORMAT**\n\n* **TARGET LANGUAGE ONLY:** If the input is Thai, output **ONLY** the final English translation. If the input is English, output **ONLY** the final Thai translation.\n* **NO META-TEXT:** Do not literal meanings, explanations, advice, opinions or any other meta-information-- OUTPUT the TRANSLATION ONLY\n* **NATURAL SPEECH:** The output must be natural, conversational speech that a native speaker would use in the same context.";

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
        if (API_KEY.isEmpty()) {
            String errorMsg = "API Key is missing. Please add it to your local.properties file.";
            Log.e(TAG, errorMsg);
            onFailure.accept(new IllegalStateException(errorMsg));
            return;
        }
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
            Log.d(TAG, "Sending config (length: " + configString.length() + ")");
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
