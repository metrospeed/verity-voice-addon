package io.github.metrospeed.verityvoice;

import net.minecraftforge.common.ForgeConfigSpec;

public final class VoiceConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    public static final ForgeConfigSpec.BooleanValue MANAGE_CHAT;
    public static final ForgeConfigSpec.ConfigValue<String> CHAT_MODEL;
    public static final ForgeConfigSpec.BooleanValue SERVER_VOICE;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_VOICE;
    public static final ForgeConfigSpec.ConfigValue<String> STT_MODEL;
    public static final ForgeConfigSpec.IntValue TTS_PER_MINUTE;
    public static final ForgeConfigSpec.IntValue STT_PER_MINUTE;
    public static final ForgeConfigSpec.IntValue MAX_TTS_CHARS;
    public static final ForgeConfigSpec.IntValue MAX_STT_SECONDS;
    public static final ForgeConfigSpec.BooleanValue USE_SERVER_VOICE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Server settings. Only the server needs these (or your own game, for singleplayer and LAN worlds).",
                "Players joining a server never need to fill anything in.").push("server");
        API_KEY = b.comment("Your OpenRouter API key (https://openrouter.ai/settings/keys).",
                "Leave empty to use the apiKey already in Verity's config.").define("openRouterKey", "");
        MANAGE_CHAT = b.comment("Point Verity's chat AI at OpenRouter with the key above.").define("manageVerityChat", true);
        CHAT_MODEL = b.comment("OpenRouter model for Verity's chat. Leave empty to keep Verity's own model setting.")
                .define("chatModel", "openai/gpt-6-luna-pro");
        SERVER_VOICE = b.comment("Let the server make Verity's voice and transcribe players' microphones.").define("serverVoice", true);
        TTS_MODEL = b.comment("OpenRouter text-to-speech model.").define("ttsModel", "mistralai/voxtral-mini-tts-3-0");
        TTS_VOICE = b.comment("Voice for the TTS model above.").define("ttsVoice", "en_paul_neutral");
        STT_MODEL = b.comment("OpenRouter speech-to-text model.").define("sttModel", "openai/whisper-large-v3-turbo");
        TTS_PER_MINUTE = b.comment("Most spoken Verity lines one player can request per minute.").defineInRange("ttsPerMinute", 30, 1, 600);
        STT_PER_MINUTE = b.comment("Most microphone clips one player can send per minute.").defineInRange("sttPerMinute", 15, 1, 600);
        MAX_TTS_CHARS = b.comment("Longest Verity line to speak, in characters.").defineInRange("maxTtsChars", 1500, 50, 10000);
        MAX_STT_SECONDS = b.comment("Longest microphone clip to transcribe, in seconds.").defineInRange("maxSttSeconds", 60, 5, 300);
        b.pop();

        b.comment("Player settings.").push("client");
        USE_SERVER_VOICE = b.comment("Use the server's voice and microphone handling when the server offers it.",
                "Turn off to keep using your own Verity voice settings.").define("useServerVoice", true);
        b.pop();

        SPEC = b.build();
    }

    /** The add-on key, or Verity's own apiKey when the add-on key is left empty. */
    public static String apiKey() {
        String key = API_KEY.get().trim();
        if (!key.isEmpty()) return key;
        String verityKey = VerityLink.getString("API_KEY");
        return verityKey == null ? "" : verityKey.trim();
    }

    private VoiceConfig() {
    }
}
