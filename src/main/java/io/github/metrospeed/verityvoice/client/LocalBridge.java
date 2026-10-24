package io.github.metrospeed.verityvoice.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.metrospeed.verityvoice.VerityVoice;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A tiny HTTP server on 127.0.0.1, inside the game, that looks like the local voice servers
 * Verity supports (KOKORO for speech, WHISPER for the microphone). Each request is handed to
 * the Minecraft server through {@link ClientVoice}, so no API key ever lives on the player's PC.
 */
final class LocalBridge {
    private static final int TIMEOUT_SECONDS = 70;
    private final HttpServer server;

    private LocalBridge(HttpServer server) {
        this.server = server;
    }

    static LocalBridge start() throws IOException {
        // Bind to 127.0.0.1 explicitly: the game runs with preferIPv6Addresses, which makes the
        // default loopback [::1], and Verity connects to the IPv4 address in its settings
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "VerityVoice-bridge");
            t.setDaemon(true);
            return t;
        }));
        LocalBridge bridge = new LocalBridge(server);
        server.createContext("/", bridge::handle);
        server.start();
        VerityVoice.LOG.info("[VerityVoice] Voice bridge listening on {}", bridge.baseUrl());
        return bridge;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            byte[] body = ex.getRequestBody().readAllBytes();
            if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/audio/speech")) {
                JsonObject req = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                String text = req.has("input") ? req.get("input").getAsString() : "";
                respond(ex, ClientVoice.requestSpeech(text), wav -> wav, "audio/wav");
            } else if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/audio/transcriptions")) {
                byte[] wav = Multipart.filePart(body, ex.getRequestHeaders().getFirst("Content-Type"));
                if (wav == null) {
                    send(ex, 400, "application/json", error("No audio in upload"));
                    return;
                }
                respond(ex, ClientVoice.requestTranscript(wav), text -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("text", text);
                    return json.toString().getBytes(StandardCharsets.UTF_8);
                }, "application/json");
            } else {
                send(ex, 404, "application/json", error("Unknown path"));
            }
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", error(e.toString()));
        }
    }

    private static <T> void respond(HttpExchange ex, CompletableFuture<T> future, Function<T, byte[]> toBody, String type) throws IOException {
        try {
            send(ex, 200, type, toBody.apply(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String why = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            VerityVoice.LOG.warn("[VerityVoice] Server voice request failed: {}", why);
            send(ex, 502, "application/json", error(why));
        }
    }

    private static byte[] error(String message) {
        JsonObject inner = new JsonObject();
        inner.addProperty("message", message);
        JsonObject json = new JsonObject();
        json.add("error", inner);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
