package io.github.metrospeed.verityvoice.server;

import io.github.metrospeed.verityvoice.OpenRouterClient;
import io.github.metrospeed.verityvoice.OpenRouterClient.Audio;
import io.github.metrospeed.verityvoice.VerityLink;
import io.github.metrospeed.verityvoice.VerityVoice;
import io.github.metrospeed.verityvoice.VoiceConfig;
import io.github.metrospeed.verityvoice.net.VoiceNetwork;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.MicAudio;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.SpeechAudio;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.SpeechRequest;
import io.github.metrospeed.verityvoice.net.VoiceNetwork.Transcript;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Server side: one OpenRouter key powers Verity's chat, voice and microphone for everyone. */
public final class ServerVoice {
    private static final long CACHE_MS = 5 * 60_000;
    private static final int MIC_BYTES_PER_SECOND = 32_000; // Verity records 16 kHz, 16-bit mono

    private static final AtomicInteger threadIds = new AtomicInteger();
    private static ExecutorService workers;
    private static MinecraftServer server;

    // Everyone near Verity asks for the same line, so each line is only bought once
    private record CachedSpeech(CompletableFuture<Audio> audio, long created) {
    }

    private static final Map<String, CachedSpeech> speechCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> ttsUse = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> sttUse = new ConcurrentHashMap<>();
    private static final Map<UUID, MicUpload> uploads = new ConcurrentHashMap<>();

    private static final class MicUpload {
        final int id, total;
        final byte[][] parts;
        int received;
        final long started = System.currentTimeMillis();

        MicUpload(int id, int total) {
            this.id = id;
            this.total = total;
            this.parts = new byte[total][];
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        workers = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "VerityVoice-" + threadIds.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        applyChatSettings(true);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (workers != null) workers.shutdownNow();
        workers = null;
        server = null;
        speechCache.clear();
        uploads.clear();
    }

    // Re-apply every few seconds, since Verity reloads its config from disk when the file changes
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 100 == 0) {
            applyChatSettings(false);
            long now = System.currentTimeMillis();
            speechCache.values().removeIf(c -> now - c.created() > CACHE_MS);
            uploads.values().removeIf(u -> now - u.started > 120_000);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && VoiceNetwork.hasAddon(player)) {
            VoiceNetwork.toPlayer(player, new VoiceNetwork.Hello(voiceEnabled()));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        uploads.remove(id);
        ttsUse.remove(id);
        sttUse.remove(id);
    }

    private static boolean voiceEnabled() {
        return VoiceConfig.SERVER_VOICE.get() && !VoiceConfig.apiKey().isEmpty();
    }

    private static void applyChatSettings(boolean announce) {
        String key = VoiceConfig.apiKey();
        if (!VoiceConfig.MANAGE_CHAT.get() || key.isEmpty()) {
            if (announce) VerityVoice.LOG.warn("[VerityVoice] No OpenRouter key set. Put one in config/verityvoice.toml (openRouterKey).");
            return;
        }
        boolean ok = VerityLink.setEnum("AI_PROVIDER", "AiProvider", "OPENROUTER") & VerityLink.set("API_KEY", key);
        String model = VoiceConfig.CHAT_MODEL.get().trim();
        if (!model.isEmpty()) ok &= VerityLink.set("USE_CUSTOM_AI_MODEL", true) & VerityLink.set("AI_MODEL", model);
        if (announce) {
            if (ok) VerityVoice.LOG.info("[VerityVoice] Verity's chat uses OpenRouter{}; server voice is {}.",
                    model.isEmpty() ? "" : " (" + model + ")", voiceEnabled() ? "on" : "off");
            else VerityVoice.LOG.warn("[VerityVoice] Couldn't apply chat settings to Verity. Is Verity 6.x installed?");
        }
    }

    // --- Speech (TTS) ---------------------------------------------------------------------

    public static void onSpeechRequest(ServerPlayer player, SpeechRequest msg) {
        if (player == null) return;
        String error = refuse(player, ttsUse, VoiceConfig.TTS_PER_MINUTE.get());
        String text = msg.text().strip();
        if (error == null && text.length() > VoiceConfig.MAX_TTS_CHARS.get()) error = "Line too long to speak";
        if (error == null && text.isEmpty()) error = "Nothing to say";
        if (error != null) {
            VoiceNetwork.toPlayer(player, new SpeechAudio(msg.id(), 0, 1, 0, 0, new byte[0], error));
            return;
        }

        String model = VoiceConfig.TTS_MODEL.get(), voice = VoiceConfig.TTS_VOICE.get(), key = VoiceConfig.apiKey();
        String cacheKey = model + "|" + voice + "|" + text;
        CachedSpeech cached = speechCache.compute(cacheKey, (k, old) ->
                old != null && !old.audio().isCompletedExceptionally() && System.currentTimeMillis() - old.created() < CACHE_MS
                        ? old
                        : new CachedSpeech(CompletableFuture.supplyAsync(() -> {
                            try {
                                return OpenRouterClient.speech(key, model, voice, text);
                            } catch (Exception e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        }, workers), System.currentTimeMillis()));

        cached.audio().whenComplete((audio, err) -> runOnServer(() -> {
            if (player.hasDisconnected()) return;
            if (err != null) {
                String why = rootMessage(err);
                VerityVoice.LOG.warn("[VerityVoice] Speech failed for {}: {}", player.getName().getString(), why);
                VoiceNetwork.toPlayer(player, new SpeechAudio(msg.id(), 0, 1, 0, 0, new byte[0], why));
                return;
            }
            byte[] pcm = audio.pcm();
            int total = Math.max(1, (pcm.length + VoiceNetwork.TO_CLIENT_CHUNK - 1) / VoiceNetwork.TO_CLIENT_CHUNK);
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(pcm, i * VoiceNetwork.TO_CLIENT_CHUNK, Math.min(pcm.length, (i + 1) * VoiceNetwork.TO_CLIENT_CHUNK));
                VoiceNetwork.toPlayer(player, new SpeechAudio(msg.id(), i, total, audio.sampleRate(), audio.channels(), part, ""));
            }
        }));
    }

    // --- Microphone (STT) -----------------------------------------------------------------

    public static void onMicAudio(ServerPlayer player, MicAudio msg) {
        if (player == null) return;
        int maxChunks = VoiceConfig.MAX_STT_SECONDS.get() * MIC_BYTES_PER_SECOND / VoiceNetwork.TO_SERVER_CHUNK + 2;
        if (msg.total() < 1 || msg.total() > maxChunks || msg.index() < 0 || msg.index() >= msg.total()) {
            uploads.remove(player.getUUID());
            VoiceNetwork.toPlayer(player, new Transcript(msg.id(), "", "Recording too long"));
            return;
        }
        MicUpload up = uploads.compute(player.getUUID(), (k, old) -> old != null && old.id == msg.id() ? old : new MicUpload(msg.id(), msg.total()));
        if (up.total != msg.total() || up.parts[msg.index()] != null) return;
        up.parts[msg.index()] = msg.data();
        if (++up.received < up.total) return;
        uploads.remove(player.getUUID());

        String error = refuse(player, sttUse, VoiceConfig.STT_PER_MINUTE.get());
        if (error != null) {
            VoiceNetwork.toPlayer(player, new Transcript(msg.id(), "", error));
            return;
        }
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        for (byte[] p : up.parts) wav.writeBytes(p);
        String key = VoiceConfig.apiKey(), model = VoiceConfig.STT_MODEL.get();
        workers.execute(() -> {
            String text = "", why = "";
            try {
                text = OpenRouterClient.transcribe(key, model, wav.toByteArray());
            } catch (Exception e) {
                why = rootMessage(e);
                VerityVoice.LOG.warn("[VerityVoice] Transcription failed for {}: {}", player.getName().getString(), why);
            }
            Transcript result = new Transcript(msg.id(), text, why);
            runOnServer(() -> {
                if (!player.hasDisconnected()) VoiceNetwork.toPlayer(player, result);
            });
        });
    }

    // --- Helpers --------------------------------------------------------------------------

    /** Returns why a request is refused, or null if it may go ahead. */
    private static String refuse(ServerPlayer player, Map<UUID, Deque<Long>> use, int perMinute) {
        if (!voiceEnabled() || workers == null) return "Server voice is off";
        Deque<Long> times = use.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
            if (times.size() >= perMinute) return "Too many requests, slow down";
            times.addLast(now);
        }
        return null;
    }

    private static void runOnServer(Runnable r) {
        MinecraftServer s = server;
        if (s != null) s.execute(r);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private ServerVoice() {
    }
}
