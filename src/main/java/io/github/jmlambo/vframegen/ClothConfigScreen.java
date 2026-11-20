package io.github.jmlambo.vframegen;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cloth Config screen. Only loaded when Cloth Config is installed. */
final class ClothConfigScreen {
    private ClothConfigScreen() {
    }

    static Screen create(Screen parent) {
        FrameGenConfig cfg = VFrameGen.config();
        FrameGenConfig def = new FrameGenConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("vframegen.config.title"))
                .setSavingRunnable(() -> {
                    cfg.save();
                    FrameGenerator.INSTANCE.invalidate();
                });
        ConfigEntryBuilder e = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(Component.translatable("vframegen.config.general"));
        general.addEntry(e.startBooleanToggle(t("enabled"), cfg.enabled)
                .setDefaultValue(def.enabled).setTooltip(tip("enabled"))
                .setSaveConsumer(v -> cfg.enabled = v).build());
        general.addEntry(e.startIntSlider(t("multiplier"), cfg.multiplier, 2, FrameGenConfig.MAX_MULTIPLIER)
                .setDefaultValue(def.multiplier).setTooltip(tip("multiplier"))
                .setTextGetter(v -> Component.literal(v + "x"))
                .setSaveConsumer(v -> cfg.multiplier = v).build());
        general.addEntry(e.startEnumSelector(t("mode"), FrameGenConfig.Mode.class, cfg.mode)
                .setDefaultValue(def.mode).setTooltip(tip("mode"))
                .setEnumNameProvider(v -> Component.translatable("vframegen.config.mode." + v.name().toLowerCase()))
                .setSaveConsumer(v -> cfg.mode = v).build());
        general.addEntry(e.startEnumSelector(t("depthMode"), FrameGenConfig.DepthMode.class, cfg.depthMode)
                .setDefaultValue(def.depthMode).setTooltip(tip("depthMode"))
                .setEnumNameProvider(v -> Component.translatable("vframegen.config.depthMode." + v.name().toLowerCase()))
                .setSaveConsumer(v -> cfg.depthMode = v).build());
        general.addEntry(e.startEnumSelector(t("pacing"), FrameGenConfig.Pacing.class, cfg.pacing)
                .setDefaultValue(def.pacing).setTooltip(tip("pacing"))
                .setEnumNameProvider(v -> Component.translatable("vframegen.config.pacing." + v.name().toLowerCase()))
                .setSaveConsumer(v -> cfg.pacing = v).build());
        general.addEntry(e.startBooleanToggle(t("presentThread"), cfg.presentThread)
                .setDefaultValue(def.presentThread).setTooltip(tip("presentThread")).requireRestart()
                .setSaveConsumer(v -> cfg.presentThread = v).build());
        general.addEntry(e.startBooleanToggle(t("autoCap"), cfg.autoCap)
                .setDefaultValue(def.autoCap).setTooltip(tip("autoCap"))
                .setSaveConsumer(v -> cfg.autoCap = v).build());
        general.addEntry(e.startIntSlider(t("displayHz"), cfg.displayHz, 0, 360)
                .setDefaultValue(def.displayHz).setTooltip(tip("displayHz"))
                .setTextGetter(v -> v == 0 ? Component.translatable("vframegen.config.displayHz.auto") : Component.literal(v + " Hz"))
                .setSaveConsumer(v -> cfg.displayHz = v).build());
        general.addEntry(e.startBooleanToggle(t("disableWhileScreenOpen"), cfg.disableWhileScreenOpen)
                .setDefaultValue(def.disableWhileScreenOpen).setTooltip(tip("disableWhileScreenOpen"))
                .setSaveConsumer(v -> cfg.disableWhileScreenOpen = v).build());

        ConfigCategory upscaling = builder.getOrCreateCategory(Component.translatable("vframegen.config.upscaling"));
        upscaling.addEntry(e.startIntSlider(t("renderScale"), (int) Math.round(cfg.renderScale * 100), 50, 100)
                .setDefaultValue((int) Math.round(def.renderScale * 100)).setTooltip(tip("renderScale"))
                .setTextGetter(v -> Component.literal(v + "%"))
                .setSaveConsumer(v -> cfg.renderScale = v / 100.0).build());
        upscaling.addEntry(e.startIntSlider(t("upscaleSharpness"), (int) Math.round(cfg.upscaleSharpness * 100), 0, 100)
                .setDefaultValue((int) Math.round(def.upscaleSharpness * 100)).setTooltip(tip("upscaleSharpness"))
                .setTextGetter(v -> Component.literal(v + "%"))
                .setSaveConsumer(v -> cfg.upscaleSharpness = v / 100.0).build());

        ConfigCategory limits = builder.getOrCreateCategory(Component.translatable("vframegen.config.limits"));
        limits.addEntry(e.startDoubleField(t("maxCameraJumpBlocks"), cfg.maxCameraJumpBlocks)
                .setDefaultValue(def.maxCameraJumpBlocks).setMin(0).setMax(64).setTooltip(tip("maxCameraJumpBlocks"))
                .setSaveConsumer(v -> cfg.maxCameraJumpBlocks = v).build());
        limits.addEntry(e.startDoubleField(t("maxCameraTurnDegrees"), cfg.maxCameraTurnDegrees)
                .setDefaultValue(def.maxCameraTurnDegrees).setMin(0).setMax(180).setTooltip(tip("maxCameraTurnDegrees"))
                .setSaveConsumer(v -> cfg.maxCameraTurnDegrees = v).build());
        limits.addEntry(e.startIntSlider(t("maxRealFrameAgeMs"), cfg.maxRealFrameAgeMs, 20, 250)
                .setDefaultValue(def.maxRealFrameAgeMs).setTooltip(tip("maxRealFrameAgeMs"))
                .setSaveConsumer(v -> cfg.maxRealFrameAgeMs = v).build());

        ConfigCategory quality = builder.getOrCreateCategory(Component.translatable("vframegen.config.quality"));
        quality.addEntry(e.startFloatField(t("sharpness"), cfg.sharpness)
                .setDefaultValue(def.sharpness).setMin(0).setMax(2).setTooltip(tip("sharpness"))
                .setSaveConsumer(v -> cfg.sharpness = v).build());
        quality.addEntry(e.startFloatField(t("guiDiffThreshold"), cfg.guiDiffThreshold)
                .setDefaultValue(def.guiDiffThreshold).setMin(0).setMax(0.5f).setTooltip(tip("guiDiffThreshold"))
                .setSaveConsumer(v -> cfg.guiDiffThreshold = v).build());

        ConfigCategory debug = builder.getOrCreateCategory(Component.translatable("vframegen.config.debug"));
        debug.addEntry(e.startBooleanToggle(t("debugTint"), cfg.debugTint)
                .setDefaultValue(def.debugTint).setTooltip(tip("debugTint"))
                .setSaveConsumer(v -> cfg.debugTint = v).build());
        debug.addEntry(e.startBooleanToggle(t("debugCaptures"), cfg.debugCaptures)
                .setDefaultValue(def.debugCaptures).setTooltip(tip("debugCaptures"))
                .setSaveConsumer(v -> cfg.debugCaptures = v).build());
        debug.addEntry(e.startBooleanToggle(t("debugFrameLog"), cfg.debugFrameLog)
                .setDefaultValue(def.debugFrameLog).setTooltip(tip("debugFrameLog"))
                .setSaveConsumer(v -> cfg.debugFrameLog = v).build());
        debug.addEntry(e.startBooleanToggle(t("debugStatsLog"), cfg.debugStatsLog)
                .setDefaultValue(def.debugStatsLog).setTooltip(tip("debugStatsLog"))
                .setSaveConsumer(v -> cfg.debugStatsLog = v).build());
        debug.addEntry(e.startBooleanToggle(t("debugPacingLog"), cfg.debugPacingLog)
                .setDefaultValue(def.debugPacingLog).setTooltip(tip("debugPacingLog"))
                .setSaveConsumer(v -> cfg.debugPacingLog = v).build());
        debug.addEntry(e.startBooleanToggle(t("debugStallLog"), cfg.debugStallLog)
                .setDefaultValue(def.debugStallLog).setTooltip(tip("debugStallLog"))
                .setSaveConsumer(v -> cfg.debugStallLog = v).build());

        return builder.build();
    }

    private static Component t(String key) {
        return Component.translatable("vframegen.config." + key);
    }

    private static Component tip(String key) {
        return Component.translatable("vframegen.config." + key + ".tooltip");
    }
}
