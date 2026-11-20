package io.github.jmlambo.vframegen;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.bind.ControlifyBindApi;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint;
import dev.isxander.controlify.api.entrypoint.InitContext;
import dev.isxander.controlify.api.entrypoint.PreInitContext;
import dev.isxander.controlify.bindings.BindContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;

/**
 * Controller buttons through Controlify: debug capture (F8) and frame generation on/off (F9).
 * Unbound by default (pick a button under Controlify's controls, VFrameGen category); both are
 * also in Controlify's radial menu. Only loaded when Controlify is installed.
 */
public final class ControlifyIntegration implements ControlifyEntrypoint {
    private static InputBindingSupplier capture;
    private static InputBindingSupplier toggle;

    @Override
    public void onControlifyPreInit(PreInitContext ctx) {
        ControlifyBindApi binds = ctx.bindings();
        Component category = Component.translatable("key.category.vframegen.main");
        capture = binds.registerBinding(b -> b.id(VFrameGen.MOD_ID, "capture")
                .name(Component.translatable("key.vframegen.capture"))
                .description(Component.translatable("vframegen.controller.capture.description"))
                .category(category)
                .allowedContexts(BindContext.IN_GAME)
                .radialCandidate(true));
        toggle = binds.registerBinding(b -> b.id(VFrameGen.MOD_ID, "toggle")
                .name(Component.translatable("key.vframegen.toggle"))
                .description(Component.translatable("vframegen.controller.toggle.description"))
                .category(category)
                .allowedContexts(BindContext.IN_GAME)
                .radialCandidate(true));
    }

    @Override
    public void onControlifyInit(InitContext ctx) {
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
                ControlifyApi.get().getCurrentController().ifPresent(controller -> {
                    var c = capture.onOrNull(controller);
                    if (c != null && c.justPressed()) VFrameGen.requestCapture(mc);
                    var t = toggle.onOrNull(controller);
                    if (t != null && t.justPressed()) VFrameGen.toggle(mc);
                }));
    }

    @Override
    public void onControllersDiscovered(ControlifyApi controlify) {
    }
}
