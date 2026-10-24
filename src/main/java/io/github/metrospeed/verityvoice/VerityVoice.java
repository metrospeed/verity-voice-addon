package io.github.metrospeed.verityvoice;

import com.mojang.logging.LogUtils;
import io.github.metrospeed.verityvoice.client.ClientVoice;
import io.github.metrospeed.verityvoice.net.VoiceNetwork;
import io.github.metrospeed.verityvoice.server.ServerVoice;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Server-side OpenRouter setup for Verity JE.
 *
 * The server admin puts one OpenRouter key in config/verityvoice.toml. The server then
 * points Verity's chat AI at OpenRouter and handles speech (TTS) and transcription (STT)
 * for every player, so players only install Verity and this add-on.
 */
@Mod(VerityVoice.MOD_ID)
public class VerityVoice {
    public static final String MOD_ID = "verityvoice";
    public static final Logger LOG = LogUtils.getLogger();

    public VerityVoice() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VoiceConfig.SPEC, "verityvoice.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(ServerVoice.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MinecraftForge.EVENT_BUS.register(ClientVoice.class));
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(VoiceNetwork::register);
    }
}
