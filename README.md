# Verity Voice (OpenRouter)

An add-on for [Verity JE](https://modrinth.com/mod/verity-je-official) 6.x on Forge 1.20.1.
The server admin enters **one OpenRouter key**, and Verity's chat, voice and microphone then
work for every player. Players just install Verity and this add-on; there's nothing to set up.

**Download:** grab `verityvoice-1.0.0.jar` from [Releases](https://github.com/metrospeed/verity-voice-addon/releases).

This is an unofficial add-on. It contains no Verity code and isn't affiliated with Verity JE's
developers or ThatMob.

## Server admin

1. Put `verityvoice-1.0.0.jar` in the server's `mods` folder next to Verity and GeckoLib.
2. Start the server once, then open `config/verityvoice.toml` and set:
   ```toml
   [server]
       openRouterKey = "sk-or-v1-..."
   ```
   (Or leave it empty to use the `apiKey` already in Verity's config.)
3. Restart the server. The log says `Verity's chat uses OpenRouter` when it's working.

Other settings in the same file: the chat, voice and transcription models, the voice,
and per-player limits (`ttsPerMinute`, `sttPerMinute`, `maxTtsChars`, `maxSttSeconds`).

## Players

Install Verity JE 6.x, its dependencies, and this add-on. That's it. When you join a server
running the add-on, Verity's voice and microphone go through the server. Your own Verity
voice settings come back when you leave. To keep using your own voice setup, set
`useServerVoice = false` under `[client]` in `config/verityvoice.toml`.

Singleplayer and LAN worlds work the same way, using the key in your own `verityvoice.toml`.

## How it works

Verity is closed source, so the add-on doesn't change its code. It only changes the same
settings you could change in Verity's config screen. A player's own voice settings are kept in
`config/verityvoice-your-voice-settings.json` while connected and put back when they leave
(or the next time they join anywhere, if the game closed while connected):

- **Chat:** on the server, Verity's AI provider is set to OpenRouter with the server's key.
  (Verity's chat already runs on the server.)
- **Voice and microphone:** Verity runs these on each player's game, and normally needs a key
  there. The add-on starts a tiny bridge inside the game on `127.0.0.1` and points Verity's
  KOKORO (speech) and WHISPER (microphone) settings at it. The bridge hands each request to
  the Minecraft server over the game connection, and the server calls OpenRouter.
- Each spoken line is cached for 5 minutes, so players standing near Verity share one request.

The server owner pays for everyone's voice use: roughly $0.001 per spoken line and a fraction
of a cent per minute of microphone audio with the default models. Players' microphone clips
pass through the server on their way to OpenRouter.

## Building

```bash
gradlew build
```

The jar lands in `build/libs/`. Needs Java 17.
