package io.github.metrospeed.verityvoice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** OpenRouter speech and transcription calls. Plain Java, so it can be tested outside the game. */
public final class OpenRouterClient {
    private static final String BASE = "https://openrouter.ai/api/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Raw 16-bit little-endian PCM audio. */
    public record Audio(byte[] pcm, int sampleRate, int channels) {
    }

    public static class OpenRouterException extends IOException {
        public OpenRouterException(String message) {
            super(message);
        }
    }

    public static Audio speech(String key, String model, String voice, String text) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("voice", voice);
        body.addProperty("input", text);
        // OpenRouter returns mp3 or pcm; the game can only play uncompressed audio
        body.addProperty("response_format", "pcm");
        HttpResponse<byte[]> r = send(key, "/audio/speech", "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
        String ctype = r.headers().firstValue("Content-Type").orElse("");
        return new Audio(r.body(), intParam(ctype, "rate", 24000), intParam(ctype, "channels", 1));
    }

    public static String transcribe(String key, String model, byte[] wav) throws IOException, InterruptedException {
        String boundary = "----VerityVoice" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 1024);
        field(out, boundary, "model", model);
        field(out, boundary, "prompt", "Verity, Minecraft, player, entity, helper, companion");
        field(out, boundary, "temperature", "0");
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> r = send(key, "/audio/transcriptions", "multipart/form-data; boundary=" + boundary, out.toByteArray());
        JsonObject json = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        return json.has("text") ? json.get("text").getAsString().trim() : "";
    }

    private static HttpResponse<byte[]> send(String key, String path, String contentType, byte[] body) throws IOException, InterruptedException {
        if (key == null || key.isBlank()) throw new OpenRouterException("No OpenRouter key set on the server");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", contentType)
                .header("X-Title", "Verity Voice add-on")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new OpenRouterException("OpenRouter " + r.statusCode() + ": " + errorMessage(r.body()));
        return r;
    }

    private static String errorMessage(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(s).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
        } catch (RuntimeException e) {
            return s.length() > 200 ? s.substring(0, 200) : s;
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static int intParam(String ctype, String name, int fallback) {
        Matcher m = Pattern.compile(name + "=(\\d+)").matcher(ctype);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private OpenRouterClient() {
    }
}
