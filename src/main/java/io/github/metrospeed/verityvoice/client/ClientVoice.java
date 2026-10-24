package io.github.metrospeed.verityvoice.client;

import io.github.metrospeed.verityvoice.VerityLink;
import io.github.metrospeed.verityvoice.VerityVoice;
import io.github.metrospeed.verityvoice.VoiceConfig;
import io.github.metrospeed.verityvoice.net.VoiceNetwork;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.Hello;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.MicAudio;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.SpeechAudio;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.SpeechRequest;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.Transcript;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client side: while connected to a server running the add-on, Verity's voice settings point at
 * {@link LocalBridge}, which hands each request to the server instead of a voice service.
 */
public final class ClientVoice {
    /** Marks Verity settings the add-on set, so stale ones can be undone after a crash. */
    private static final String MARKER = "verityvoice";
    private static final String[] FIELDS = {"TTS_PROVIDER", "TTS_ENDPOINT", "KOKORO_MODEL", "ENDPOINT_VOICE", "STT_PROVIDER", "STT_ENDPOINT", "STT_MODEL"};
    private static final Map<String, String> ENUM_TYPES = Map.of("TTS_PROVIDER", "TTSProvider", "STT_PROVIDER", "STTProvider");

    private static LocalBridge bridge;
    private static boolean active;
    // The player's own voice settings, kept on disk too because Verity saves its config file
    // whenever a setting changes, so the add-on's values can outlive a crash
    private static final Map<String, String> saved = new LinkedHashMap<>();
    private static final Path SAVED_FILE = FMLPaths.CONFIGDIR.get().resolve("verityvoice-your-voice-settings.json");

    private static final AtomicInteger ids = new AtomicInteger();
    private static final Map<Integer, PendingSpeech> speech = new ConcurrentHashMap<>();
    private static final Map<Integer, CompletableFuture<String>> transcripts = new ConcurrentHashMap<>();

    private static final class PendingSpeech {
        final CompletableFuture<byte[]> wav = new CompletableFuture<>();
        byte[][] parts;
        int received;
    }

    // --- Turning server voice on and off ----------------------------------------------------

    public static void onHello(Hello msg) {
        if (msg.voice() && VoiceConfig.USE_SERVER_VOICE.get()) {
            try {
                if (bridge == null) bridge = LocalBridge.start();
            } catch (IOException e) {
                VerityVoice.LOG.error("[VerityVoice] Couldn't start the local voice bridge", e);
                return;
            }
            active = true;
            applyVoiceSettings();
            VerityVoice.LOG.info("[VerityVoice] Server handles Verity's voice and microphone.");
        } else {
            deactivate();
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Undo settings left behind if the game closed while connected
        if (!active && MARKER.equals(VerityLink.getString("KOKORO_MODEL"))) {
            loadSaved();
            if (saved.isEmpty()) resetLeftovers();
            else restoreSaved();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        deactivate();
    }

    // Verity reloads its config when the file changes (for example from its settings screen)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && active && Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getGameTime() % 100 == 0) {
            applyVoiceSettings();
        }
    }

    private static void applyVoiceSettings() {
        String url = bridge.baseUrl();
        if (saved.isEmpty() && !MARKER.equals(VerityLink.getString("KOKORO_MODEL"))) {
            for (String f : FIELDS) {
                Object v = VerityLink.get(f);
                if (v != null) saved.put(f, v instanceof Enum<?> e ? e.name() : v.toString());
            }
            writeSaved();
        }
        VerityLink.setEnum("TTS_PROVIDER", "TTSProvider", "KOKORO");
        VerityLink.set("TTS_ENDPOINT", url);
        VerityLink.set("KOKORO_MODEL", MARKER);
        VerityLink.set("ENDPOINT_VOICE", "server");
        VerityLink.setEnum("STT_PROVIDER", "STTProvider", "WHISPER");
        VerityLink.set("STT_ENDPOINT", url);
        VerityLink.set("STT_MODEL", MARKER);
    }

    private static void deactivate() {
        if (active) restoreSaved();
        active = false;
        speech.values().forEach(p -> p.wav.completeExceptionally(new IOException("Disconnected")));
        speech.clear();
        transcripts.values().forEach(f -> f.completeExceptionally(new IOException("Disconnected")));
        transcripts.clear();
    }

    private static void restoreSaved() {
        saved.forEach((field, value) -> {
            if (ENUM_TYPES.containsKey(field)) VerityLink.setEnum(field, ENUM_TYPES.get(field), value);
            else VerityLink.set(field, value);
        });
        saved.clear();
        try {
            Files.deleteIfExists(SAVED_FILE);
        } catch (IOException e) {
            VerityVoice.LOG.warn("[VerityVoice] Couldn't delete {}", SAVED_FILE, e);
        }
    }

    private static void writeSaved() {
        try {
            Files.writeString(SAVED_FILE, new GsonBuilder().setPrettyPrinting().create().toJson(saved));
        } catch (IOException e) {
            VerityVoice.LOG.warn("[VerityVoice] Couldn't save your voice settings to {}", SAVED_FILE, e);
        }
    }

    private static void loadSaved() {
        saved.clear();
        if (!Files.exists(SAVED_FILE)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(SAVED_FILE)).getAsJsonObject();
            for (String f : FIELDS) {
                if (json.has(f)) saved.put(f, json.get(f).getAsString());
            }
        } catch (IOException | RuntimeException e) {
            VerityVoice.LOG.warn("[VerityVoice] Couldn't read {}", SAVED_FILE, e);
        }
    }

    // No saved copy to go back to, so use Verity's defaults
    private static void resetLeftovers() {
        VerityLink.setEnum("TTS_PROVIDER", "TTSProvider", "LOCAL");
        VerityLink.set("TTS_ENDPOINT", "");
        VerityLink.set("KOKORO_MODEL", "");
        VerityLink.setEnum("STT_PROVIDER", "STTProvider", "NATIVE");
        VerityLink.set("STT_ENDPOINT", "");
        VerityLink.set("STT_MODEL", "");
    }

    // --- Requests from the bridge (called off the game thread) -----------------------------

    static CompletableFuture<byte[]> requestSpeech(String text) {
        if (!active) return CompletableFuture.failedFuture(new IOException("Not connected to a voice server"));
        int id = ids.incrementAndGet();
        PendingSpeech p = new PendingSpeech();
        speech.put(id, p);
        Minecraft.getInstance().execute(() -> VoiceNetwork.CHANNEL.sendToServer(new SpeechRequest(id, text)));
        return p.wav.whenComplete((r, e) -> speech.remove(id));
    }

    static CompletableFuture<String> requestTranscript(byte[] wav) {
        if (!active) return CompletableFuture.failedFuture(new IOException("Not connected to a voice server"));
        int id = ids.incrementAndGet();
        CompletableFuture<String> f = new CompletableFuture<>();
        transcripts.put(id, f);
        int chunk = VoiceNetwork.TO_SERVER_CHUNK, total = Math.max(1, (wav.length + chunk - 1) / chunk);
        Minecraft.getInstance().execute(() -> {
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(wav, i * chunk, Math.min(wav.length, (i + 1) * chunk));
                VoiceNetwork.CHANNEL.sendToServer(new MicAudio(id, i, total, part));
            }
        });
        return f.whenComplete((r, e) -> transcripts.remove(id));
    }

    // --- Replies from the server -----------------------------------------------------------

    public static void onSpeechAudio(SpeechAudio msg) {
        PendingSpeech p = speech.get(msg.id());
        if (p == null) return;
        if (!msg.error().isEmpty()) {
            p.wav.completeExceptionally(new IOException(msg.error()));
            return;
        }
        if (p.parts == null) p.parts = new byte[msg.total()][];
        if (msg.index() >= p.parts.length || p.parts[msg.index()] != null) return;
        p.parts[msg.index()] = msg.data();
        if (++p.received < p.parts.length) return;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (byte[] part : p.parts) pcm.writeBytes(part);
        p.wav.complete(Wav.fromPcm(pcm.toByteArray(), msg.sampleRate(), msg.channels()));
    }

    public static void onTranscript(Transcript msg) {
        CompletableFuture<String> f = transcripts.get(msg.id());
        if (f == null) return;
        if (msg.error().isEmpty()) f.complete(msg.text());
        else f.completeExceptionally(new IOException(msg.error()));
    }

    private ClientVoice() {
    }
}
