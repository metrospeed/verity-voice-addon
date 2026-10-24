package io.github.metrospeed.verityvoice.client;

import io.github.metrospeed.verityvoice.OpenRouterClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the add-on's non-Minecraft pieces against the real OpenRouter API.
 * Usage: OfflineCheck <path to a verity-common.toml or verityvoice.toml holding the key>
 */
public class OfflineCheck {
    public static void main(String[] args) throws Exception {
        String text = Files.readString(Path.of(args[0]));
        Matcher m = Pattern.compile("(?m)^\\s*(?:openRouterKey|apiKey)\\s*=\\s*\"(.+?)\"").matcher(text);
        String key = m.find() ? m.group(1) : "";

        // 1. Upload parsing: build the exact multipart body Verity's WHISPER provider sends
        byte[] fakeWav = Wav.fromPcm(new byte[32000], 16000, 1);
        String bd = "----VerityBoundary1792788999";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (String[] f : new String[][]{{"model", "verityvoice"}, {"prompt", "Verity, Minecraft"}, {"temperature", "0.0"}}) {
            body.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fakeWav);
        body.write(("\r\n--" + bd + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] extracted = Multipart.filePart(body.toByteArray(), "multipart/form-data; boundary=" + bd);
        System.out.println("multipart: extracted " + (extracted == null ? "null" : extracted.length) + " bytes, matches=" + Arrays.equals(extracted, fakeWav));

        // 2. Speech, then feed the speech back through transcription
        try {
            OpenRouterClient.Audio audio = OpenRouterClient.speech(key, "mistralai/voxtral-mini-tts-3-0", "en_paul_neutral",
                    "Hello. I'm Verity, your personal helper friend.");
            System.out.printf("speech: %d bytes, %d Hz, %d ch, %.1fs%n", audio.pcm().length, audio.sampleRate(), audio.channels(),
                    audio.pcm().length / 2.0 / audio.channels() / audio.sampleRate());
            byte[] wav = Wav.fromPcm(audio.pcm(), audio.sampleRate(), audio.channels());
            if (args.length > 1) Files.write(Path.of(args[1]), wav);
            System.out.println("transcribe: \"" + OpenRouterClient.transcribe(key, "openai/whisper-large-v3-turbo", wav) + "\"");
        } catch (Exception e) {
            System.out.println("openrouter error (as players would see it): " + e.getMessage());
        }
    }
}
