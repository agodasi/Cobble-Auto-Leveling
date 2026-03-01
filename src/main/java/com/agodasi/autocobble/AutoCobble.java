package com.agodasi.autocobble;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * AutoCobble - Automated Cobblemon helper mod.
 * メインエントリーポイント。
 */
@Mod(AutoCobble.MOD_ID)
public class AutoCobble {

    public static final String MOD_ID = "autocobble";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AutoCobble(FMLJavaModLoadingContext context) {
        // クライアント側のみ初期化
        if (FMLEnvironment.dist == Dist.CLIENT) {
            context.getModEventBus().addListener(this::onClientSetup);
            context.getModEventBus().addListener(this::onRegisterKeyMappings);
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        }
    }

    /**
     * クライアント初期化処理。
     */
    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("AutoCobble initialized");
    }

    /**
     * キーバインド登録処理。
     */
    private void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        event.register(KeyBindings.TOGGLE_KEY);
    }
}
