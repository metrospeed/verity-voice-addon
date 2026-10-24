package io.github.metrospeed.verityvoice.net;

import io.github.metrospeed.verityvoice.VerityVoice;
import io.github.metrospeed.verityvoice.server.ServerVoice;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * Messages between the game and the server. Players without the add-on can still join
 * (the channel is optional on both sides); they just keep their own Verity voice settings.
 */
public final class VoiceNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VerityVoice.MOD_ID, "voice"), () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL), NetworkRegistry.acceptMissingOr(PROTOCOL));

    /** Serverbound packets are capped at 32 KiB by Minecraft, clientbound at 1 MiB. */
    public static final int TO_SERVER_CHUNK = 30_000;
    public static final int TO_CLIENT_CHUNK = 900_000;
    private static final int MAX_TEXT = 10_000;

    /** Server → client: whether the server handles Verity's voice. Sent on join. */
    public record Hello(boolean voice) {
        Hello(FriendlyByteBuf buf) { this(buf.readBoolean()); }
        void encode(FriendlyByteBuf buf) { buf.writeBoolean(voice); }
    }

    /** Client → server: speak this line of Verity's. */
    public record SpeechRequest(int id, String text) {
        SpeechRequest(FriendlyByteBuf buf) { this(buf.readVarInt(), buf.readUtf(MAX_TEXT)); }
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(id); buf.writeUtf(text, MAX_TEXT); }
    }

    /** Server → client: one piece of the spoken audio, or an error. */
    public record SpeechAudio(int id, int index, int total, int sampleRate, int channels, byte[] data, String error) {
        SpeechAudio(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(TO_CLIENT_CHUNK), buf.readUtf(1000));
        }
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(id).writeVarInt(index).writeVarInt(total).writeVarInt(sampleRate).writeVarInt(channels);
            buf.writeByteArray(data);
            buf.writeUtf(error, 1000);
        }
    }

    /** Client → server: one piece of a microphone recording (WAV). */
    public record MicAudio(int id, int index, int total, byte[] data) {
        MicAudio(FriendlyByteBuf buf) { this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(TO_SERVER_CHUNK)); }
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(id).writeVarInt(index).writeVarInt(total); buf.writeByteArray(data); }
    }

    /** Server → client: what the player said, or an error. */
    public record Transcript(int id, String text, String error) {
        Transcript(FriendlyByteBuf buf) { this(buf.readVarInt(), buf.readUtf(MAX_TEXT), buf.readUtf(1000)); }
        void encode(FriendlyByteBuf buf) { buf.writeVarInt(id); buf.writeUtf(text, MAX_TEXT); buf.writeUtf(error, 1000); }
    }

    public static void register() {
        int i = 0;
        CHANNEL.messageBuilder(Hello.class, i++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Hello::encode).decoder(Hello::new).consumerMainThread(VoiceNetwork::onHello).add();
        CHANNEL.messageBuilder(SpeechRequest.class, i++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SpeechRequest::encode).decoder(SpeechRequest::new)
                .consumerMainThread((msg, ctx) -> ServerVoice.onSpeechRequest(ctx.get().getSender(), msg)).add();
        CHANNEL.messageBuilder(SpeechAudio.class, i++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SpeechAudio::encode).decoder(SpeechAudio::new).consumerMainThread(VoiceNetwork::onSpeechAudio).add();
        CHANNEL.messageBuilder(MicAudio.class, i++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MicAudio::encode).decoder(MicAudio::new)
                .consumerMainThread((msg, ctx) -> ServerVoice.onMicAudio(ctx.get().getSender(), msg)).add();
        CHANNEL.messageBuilder(Transcript.class, i++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Transcript::encode).decoder(Transcript::new).consumerMainThread(VoiceNetwork::onTranscript).add();
    }

    public static boolean hasAddon(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static void toPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    // Client handlers go through DistExecutor so the dedicated server never loads client classes
    private static void onHello(Hello msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> io.github.metrospeed.verityvoice.client.ClientVoice.onHello(msg));
    }

    private static void onSpeechAudio(SpeechAudio msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> io.github.metrospeed.verityvoice.client.ClientVoice.onSpeechAudio(msg));
    }

    private static void onTranscript(Transcript msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> io.github.metrospeed.verityvoice.client.ClientVoice.onTranscript(msg));
    }

    private VoiceNetwork() {
    }
}
