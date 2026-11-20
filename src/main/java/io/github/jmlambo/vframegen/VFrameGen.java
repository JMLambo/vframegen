package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VFrameGen implements ClientModInitializer {
    public static final String MOD_ID = "vframegen";
    public static final Logger LOGGER = LoggerFactory.getLogger("VFrameGen");

    private static FrameGenConfig config = new FrameGenConfig();

    public static FrameGenConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        config = FrameGenConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.vframegen.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) {
                config.enabled = !config.enabled;
                config.save();
                FrameGenerator.INSTANCE.invalidate();
                if (mc.player != null) {
                    mc.player.sendOverlayMessage(Component.translatable(
                            config.enabled ? "vframegen.toggle.on" : "vframegen.toggle.off",
                            FrameGenerator.INSTANCE.backendName()));
                }
            }
        });

        LOGGER.info("VFrameGen loaded (enabled={}, depthMode={}, pacing={})",
                config.enabled, config.depthMode, config.pacing);
    }
}
