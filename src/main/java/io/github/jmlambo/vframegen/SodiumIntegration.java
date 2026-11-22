package io.github.jmlambo.vframegen;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A VFrameGen page in Sodium's video settings, with the settings people change most. The rest
 * (limits, reprojection, debug) stay in the full settings screen, linked from the page.
 */
public final class SodiumIntegration implements ConfigEntryPoint {
    private Presets.Preset pickedPreset;

    @Override
    public void registerConfigLate(ConfigBuilder b) {
        FrameGenConfig cfg = VFrameGen.config();
        FrameGenConfig def = new FrameGenConfig();
        StorageEventHandler storage = () -> {
            // A preset picked: it wins over the individual settings saved with it.
            if (pickedPreset != null) {
                Presets.apply(cfg, pickedPreset);
                pickedPreset = null;
                // The page still shows the values from before the preset: reload them, once
                // Sodium is done applying.
                Minecraft.getInstance().execute(SodiumIntegration::reloadPage);
            }
            cfg.save();
            FrameGenerator.afterConfigSaved(Minecraft.getInstance());
        };

        OptionGroupBuilder general = b.createOptionGroup()
                .addOption(b.createEnumOption(id("preset"), Presets.Preset.class)
                        .setName(t("preset")).setTooltip(tip("preset"))
                        .setElementNameProvider(v -> enumName("preset", v))
                        .setDefaultValue(Presets.Preset.CUSTOM).setStorageHandler(storage)
                        .setBinding(v -> pickedPreset = v == Presets.match(cfg) ? null : v, () -> Presets.match(cfg)))
                .addOption(bool(b, storage, "enabled", def.enabled, v -> cfg.enabled = v, () -> cfg.enabled)
                        .setImpact(OptionImpact.MEDIUM))
                .addOption(bool(b, storage, "adaptive", def.adaptive, v -> cfg.adaptive = v, () -> cfg.adaptive))
                .addOption(b.createIntegerOption(id("multiplier"))
                        .setName(Component.translatable("vframegen.sodium.multiplier")).setTooltip(tip("multiplier"))
                        .setRange(2, FrameGenConfig.MAX_MULTIPLIER, 1)
                        .setValueFormatter(v -> Component.literal(v + "x"))
                        .setDefaultValue(def.multiplier).setStorageHandler(storage)
                        .setEnabledProvider(s -> !s.readBooleanOption(id("adaptive")), id("adaptive"))
                        .setBinding(v -> cfg.multiplier = v, () -> cfg.multiplier))
                .addOption(b.createIntegerOption(id("targetFps"))
                        .setName(t("targetFps")).setTooltip(tip("targetFps"))
                        .setRange(30, FrameGenConfig.MAX_TARGET_FPS, 1)
                        .setValueFormatter(v -> Component.literal(v + " fps"))
                        .setDefaultValue(def.targetFps).setStorageHandler(storage)
                        .setEnabledProvider(s -> s.readBooleanOption(id("adaptive")) || s.readBooleanOption(id("dynamicRenderScale")),
                                id("adaptive"), id("dynamicRenderScale"))
                        .setBinding(v -> cfg.targetFps = v, () -> cfg.targetFps))
                .addOption(b.createEnumOption(id("mode"), FrameGenConfig.Mode.class)
                        .setName(t("mode")).setTooltip(tip("mode"))
                        .setElementNameProvider(v -> enumName("mode", v))
                        .setDefaultValue(def.mode).setStorageHandler(storage)
                        .setBinding(v -> cfg.mode = v, () -> cfg.mode));

        OptionGroupBuilder upscaling = b.createOptionGroup()
                .setName(t("upscaling"))
                .addOption(bool(b, storage, "dynamicRenderScale", def.dynamicRenderScale, v -> cfg.dynamicRenderScale = v, () -> cfg.dynamicRenderScale))
                .addOption(b.createIntegerOption(id("renderScale"))
                        .setName(t("renderScale")).setTooltip(tip("renderScale"))
                        .setRange(50, 100, 1)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setImpact(OptionImpact.HIGH)
                        .setEnabledProvider(st -> !st.readBooleanOption(id("dynamicRenderScale")), id("dynamicRenderScale"))
                        .setDefaultValue((int) Math.round(def.renderScale * 100)).setStorageHandler(storage)
                        .setBinding(v -> cfg.renderScale = v / 100.0, () -> (int) Math.round(cfg.renderScale * 100)))
                .addOption(b.createEnumOption(id("upscaler"), FrameGenConfig.Upscaler.class)
                        .setName(t("upscaler")).setTooltip(tip("upscaler"))
                        .setElementNameProvider(v -> enumName("upscaler", v))
                        .setDefaultValue(def.upscaler).setStorageHandler(storage)
                        .setBinding(v -> cfg.upscaler = v, () -> cfg.upscaler))
                .addOption(b.createIntegerOption(id("upscaleSharpness"))
                        .setName(t("upscaleSharpness")).setTooltip(tip("upscaleSharpness"))
                        .setRange(0, 100, 1)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setDefaultValue((int) Math.round(def.upscaleSharpness * 100)).setStorageHandler(storage)
                        .setBinding(v -> cfg.upscaleSharpness = v / 100.0, () -> (int) Math.round(cfg.upscaleSharpness * 100)));

        OptionGroupBuilder quality = b.createOptionGroup()
                .setName(t("quality"))
                .addOption(b.createEnumOption(id("antiAliasing"), FrameGenConfig.AntiAliasingMode.class)
                        .setName(t("antiAliasing")).setTooltip(tip("antiAliasing"))
                        .setElementNameProvider(v -> enumName("antiAliasing", v))
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(def.antiAliasing).setStorageHandler(storage)
                        // Temporal upscaling anti-aliases on its own.
                        .setEnabledProvider(s -> Compat.irisShadersActive()
                                || s.readEnumOption(id("upscaler"), FrameGenConfig.Upscaler.class) != FrameGenConfig.Upscaler.TEMPORAL,
                                id("upscaler"))
                        .setBinding(v -> cfg.antiAliasing = v, () -> cfg.antiAliasing))
                .addOption(b.createIntegerOption(id("antiAliasingQuality"))
                        .setName(t("antiAliasingQuality")).setTooltip(tip("antiAliasingQuality"))
                        .setRange(1, 4, 1)
                        .setValueFormatter(v -> Component.translatable("vframegen.config.antiAliasingQuality." + v))
                        .setDefaultValue(def.antiAliasingQuality).setStorageHandler(storage)
                        .setEnabledProvider(s -> s.readEnumOption(id("antiAliasing"), FrameGenConfig.AntiAliasingMode.class)
                                != FrameGenConfig.AntiAliasingMode.OFF
                                && (Compat.irisShadersActive()
                                || s.readEnumOption(id("upscaler"), FrameGenConfig.Upscaler.class) != FrameGenConfig.Upscaler.TEMPORAL),
                                id("antiAliasing"), id("upscaler"))
                        .setBinding(v -> cfg.antiAliasingQuality = v, () -> cfg.antiAliasingQuality))
                .addOption(bool(b, storage, "motionBlur", def.motionBlur, v -> cfg.motionBlur = v, () -> cfg.motionBlur))
                .addOption(b.createIntegerOption(id("motionBlurStrength"))
                        .setName(t("motionBlurStrength")).setTooltip(tip("motionBlurStrength"))
                        .setRange(10, 100, 1)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setDefaultValue(Math.round(def.motionBlurStrength * 100)).setStorageHandler(storage)
                        .setEnabledProvider(s -> s.readBooleanOption(id("motionBlur")), id("motionBlur"))
                        .setBinding(v -> cfg.motionBlurStrength = v / 100f, () -> Math.round(cfg.motionBlurStrength * 100)));

        var page = b.createOptionPage()
                .setName(Component.translatable("vframegen.sodium.page"))
                .addOptionGroup(general)
                .addOptionGroup(upscaling)
                .addOptionGroup(quality);
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            page.addOptionGroup(b.createOptionGroup()
                    .addOption(b.createExternalButtonOption(id("allSettings"))
                            .setName(Component.translatable("vframegen.sodium.allSettings"))
                            .setTooltip(Component.translatable("vframegen.sodium.allSettings.tooltip"))
                            .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(ClothConfigScreen.create(parent)))));
        }

        b.registerOwnModOptions()
                .setName("VFrameGen")
                .setNonTintedIcon(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "icon.png"))
                .addPage(page);
    }

    private static void reloadPage() {
        try {
            var config = net.caffeinemc.mods.sodium.client.config.ConfigManager.CONFIG;
            if (config != null) config.resetAllOptionsFromBindings();
        } catch (RuntimeException | LinkageError e) {
            // Sodium internals: if they change, the page just updates when reopened.
            VFrameGen.LOGGER.debug("Could not refresh Sodium's options page", e);
        }
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder bool(
            ConfigBuilder b, StorageEventHandler storage, String key, boolean def, Consumer<Boolean> set, Supplier<Boolean> get) {
        return b.createBooleanOption(id(key))
                .setName(t(key)).setTooltip(tip(key))
                .setDefaultValue(def).setStorageHandler(storage)
                .setBinding(set, get);
    }

    private static Identifier id(String key) {
        return Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, key.toLowerCase(Locale.ROOT));
    }

    private static Component enumName(String key, Enum<?> v) {
        return Component.translatable("vframegen.config." + key + "." + v.name().toLowerCase(Locale.ROOT));
    }

    private static Component t(String key) {
        return Component.translatable("vframegen.config." + key);
    }

    private static Component tip(String key) {
        return Component.translatable("vframegen.config." + key + ".tooltip");
    }
}
